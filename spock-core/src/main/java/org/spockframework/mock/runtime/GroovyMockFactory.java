/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.spockframework.mock.runtime;

import org.spockframework.mock.*;
import org.spockframework.runtime.GroovyRuntimeUtil;
import org.spockframework.runtime.RunContext;
import org.spockframework.util.ReflectionUtil;
import org.spockframework.util.SpockDocLinks;
import spock.lang.Specification;

import java.lang.reflect.Modifier;

import groovy.lang.*;

public class GroovyMockFactory implements IMockFactory {
  public static final GroovyMockFactory INSTANCE = new GroovyMockFactory();

  @Override
  public boolean canCreate(IMockConfiguration configuration) {
    return configuration.getImplementation() == MockImplementation.GROOVY;
  }

  @Override
  public Object create(IMockConfiguration configuration, Specification specification) throws CannotCreateMockException {
    final MetaClass oldMetaClass = GroovyRuntimeUtil.getMetaClass(configuration.getType());
    GroovyMockMetaClass newMetaClass = new GroovyMockMetaClass(configuration, specification, oldMetaClass);
    final Class<?> type = configuration.getType();

    boolean hasAdditionalInterfaces = !configuration.getAdditionalInterfaces().isEmpty();
    if (configuration.isGlobal()) {
      if (type.isInterface()) {
        throw new CannotCreateMockException(type,
            ". Global mocking is only possible for classes, but not for interfaces.");
      }
      if (hasAdditionalInterfaces) {
        throw new CannotCreateMockException(type,
          ". Global cannot add additionalInterfaces.");
      }
      GroovyRuntimeUtil.setMetaClass(type, newMetaClass);
      specification.getSpecificationContext().getCurrentIteration().addCleanup(() -> GroovyRuntimeUtil.setMetaClass(type, oldMetaClass));
      return MockInstantiator.instantiate(type, type, configuration.getConstructorArgs(), configuration.isUseObjenesis());
    }

    if (isFinalClass(type)) {
      if (hasAdditionalInterfaces) {
        throw new CannotCreateMockException(type,
          ". Cannot add additionalInterfaces to final classes.");
      }
      final Object instance = MockInstantiator.instantiate(type,
          type, configuration.getConstructorArgs(), configuration.isUseObjenesis());
      GroovyRuntimeUtil.setMetaClass(instance, newMetaClass);

      return instance;
    }

    GroovyMockInterceptor mockInterceptor = new GroovyMockInterceptor(configuration, specification, newMetaClass);
    IMockMaker.IMockCreationSettings mockCreationSettings = MockCreationSettings.settingsFromMockConfiguration(configuration,
      mockInterceptor,
      specification.getClass().getClassLoader());
    mockCreationSettings.getAdditionalInterface().add(GroovyObject.class);
    Object proxy = RunContext.get().getMockMakerRegistry().makeMock(mockCreationSettings);

    if (hasAdditionalInterfaces) {
      //Issue #1405: We need to update the mockMetaClass to reflect the methods of the additional interfaces
      //             The MetaClass of the mock is a bit too much, but we do not have a class representing the hierarchy without the internal Spock interfaces like ISpockMockObject
      MetaClass oldMetaClassOfProxy = GroovyRuntimeUtil.getMetaClass(proxy.getClass());
      GroovyMockMetaClass mockMetaClass = new GroovyMockMetaClass(configuration, specification, oldMetaClassOfProxy);
      mockInterceptor.setMetaClass(mockMetaClass);
    }

    if ((configuration.getNature() == MockNature.SPY) && (configuration.getInstance() != null)) {
      try {
        ReflectionUtil.deepCopyFields(configuration.getInstance(), proxy);
      } catch (Exception e) {
        throw new CannotCreateMockException(type,
          ". Cannot copy fields.\n" + SpockDocLinks.SPY_ON_JAVA_17.getLink(),
          e);
      }
    }
    return proxy;
  }

  private boolean isFinalClass(Class<?> type) {
    return !type.isInterface() && Modifier.isFinal(type.getModifiers());
  }

  @Override
  public Object createDetached(IMockConfiguration configuration, ClassLoader classLoader) {
    throw new CannotCreateMockException(configuration.getType(),
        ". Detached mocking is only possible for JavaMocks but not GroovyMocks at the moment.");
  }
}
