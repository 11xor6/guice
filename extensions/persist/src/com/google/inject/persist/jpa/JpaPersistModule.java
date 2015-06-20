/**
 * Copyright (C) 2010 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.persist.jpa;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.persist.PersistModule;
import com.google.inject.persist.PersistService;
import com.google.inject.persist.UnitOfWork;
import com.google.inject.persist.finder.DynamicFinder;
import com.google.inject.persist.finder.Finder;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

/**
 * JPA provider for guice persist.
 *
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 * @author Nik Hodgkinson (11xor6@gmail.com)
 */
public final class JpaPersistModule extends PersistModule {
  private final String jpaUnit;

  public JpaPersistModule(String jpaUnit) {
    Preconditions.checkArgument(null != jpaUnit && jpaUnit.length() > 0,
        "JPA unit name must be a non-empty string.");
    this.jpaUnit = jpaUnit;
  }

  private Provider<Map<?, ?>> propertiesProvider;
  private Class<? extends Provider<Map<?, ?>>> propertiesProviderClass;

  private MethodInterceptor transactionInterceptor;

  @Override protected void configurePersistence() {
    bindConstant().annotatedWith(Jpa.class).to(jpaUnit);

    LinkedBindingBuilder<Map<?, ?>> jpaPropertiesBinder =
            bind(Key.get(new TypeLiteral<Map<?, ?>>() {}, Jpa.class));
    if (propertiesProvider != null) {
      jpaPropertiesBinder.toProvider(propertiesProvider);
    } else if (propertiesProviderClass != null) {
      jpaPropertiesBinder.toProvider(propertiesProviderClass);
    } else {
      jpaPropertiesBinder.toProvider(new JpaPropertiesProvider(null));
    }

    bind(JpaPersistService.class).in(Singleton.class);

    bind(PersistService.class).to(JpaPersistService.class);
    bind(UnitOfWork.class).to(JpaPersistService.class);
    bind(EntityManager.class).toProvider(JpaPersistService.class);
    bind(EntityManagerFactory.class)
        .toProvider(JpaPersistService.EntityManagerFactoryProvider.class);

    transactionInterceptor = new JpaLocalTxnInterceptor();
    requestInjection(transactionInterceptor);

    // Bind dynamic finders.
    for (Class<?> finder : dynamicFinders) {
      bindFinder(finder);
    }
  }

  @Override protected MethodInterceptor getTransactionInterceptor() {
    return transactionInterceptor;
  }

  /**
   * Configures the JPA persistence provider with a set of properties.
   * 
   * @param properties A set of name value pairs that configure a JPA persistence
   *     provider as per the specification.
   * @since 4.0 (since 3.0 with a parameter type of {@code java.util.Properties})
   */
  public JpaPersistModule properties(final Map<?,?> properties) {
    return this.properties(new JpaPropertiesProvider(properties));
  }

  /**
   * Configures the JPA persistence provider via a provider allowing for injection
   * of properties from an external source at injector creation time.
   *
   * @param propertiesProvider A provider that supplies properties for JPA configuration.
   */
  public JpaPersistModule properties(Provider<Map<?, ?>> propertiesProvider) {
    this.propertiesProvider = Preconditions.checkNotNull(propertiesProvider);
    this.propertiesProviderClass = null;
    return this;
  }

  /**
   * Configures the JPA persistence provider via a provider allowing for injection
   * of properties from an external source at injector creation time.
   *
   * @param propertiesProviderClass A provider class that supplies properties
   *                                for JPA configuration.
   */
  public JpaPersistModule properties(Class<? extends Provider<Map<?, ?>>> propertiesProviderClass) {
    this.propertiesProviderClass = Preconditions.checkNotNull(propertiesProviderClass);
    this.propertiesProvider = null;
    return this;
  }

  private final List<Class<?>> dynamicFinders = Lists.newArrayList();

  /**
   * Adds an interface to this module to use as a dynamic finder.
   *
   * @param iface Any interface type whose methods are all dynamic finders.
   */
  public <T> JpaPersistModule addFinder(Class<T> iface) {
    dynamicFinders.add(iface);
    return this;
  }

  private <T> void bindFinder(Class<T> iface) {
    if (!isDynamicFinderValid(iface)) {
      return;
    }

    InvocationHandler finderInvoker = new InvocationHandler() {
      @Inject JpaFinderProxy finderProxy;

      public Object invoke(final Object thisObject, final Method method, final Object[] args)
          throws Throwable {

        // Don't intercept non-finder methods like equals and hashcode.
        if (!method.isAnnotationPresent(Finder.class)) {
          // NOTE(dhanji): This is not ideal, we are using the invocation handler's equals
          // and hashcode as a proxy (!) for the proxy's equals and hashcode. 
          return method.invoke(this, args);
        }

        return finderProxy.invoke(new MethodInvocation() {
          public Method getMethod() {
            return method;
          }

          public Object[] getArguments() {
            return null == args ? new Object[0] : args; 
          }

          public Object proceed() throws Throwable {
            return method.invoke(thisObject, args);
          }

          public Object getThis() {
            throw new UnsupportedOperationException("Bottomless proxies don't expose a this.");
          }

          public AccessibleObject getStaticPart() {
            throw new UnsupportedOperationException();
          }
        });
      }
    };
    requestInjection(finderInvoker);

    @SuppressWarnings("unchecked") // Proxy must produce instance of type given.
    T proxy = (T) Proxy
        .newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { iface },
            finderInvoker);

    bind(iface).toInstance(proxy);
  }

  private boolean isDynamicFinderValid(Class<?> iface) {
    boolean valid = true;
    if (!iface.isInterface()) {
      addError(iface + " is not an interface. Dynamic Finders must be interfaces.");
      valid = false;
    }

    for (Method method : iface.getMethods()) {
      DynamicFinder finder = DynamicFinder.from(method);
      if (null == finder) {
        addError("Dynamic Finder methods must be annotated with @Finder, but " + iface
            + "." + method.getName() + " was not");
        valid = false;
      }
    }
    return valid;
  }

  private final class JpaPropertiesProvider implements Provider<Map<?, ?>> {
    private final Map<?, ?> properties;

    public JpaPropertiesProvider(final Map<?, ?> properties) {
      this.properties = properties;
    }

    @Override
    public Map<?, ?> get() {
      return properties;
    }
  }
}
