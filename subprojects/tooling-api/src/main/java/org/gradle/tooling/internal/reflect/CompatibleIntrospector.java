/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.reflect;

import org.gradle.tooling.model.UnsupportedMethodException;

import java.lang.reflect.Method;

/**
 * Uses reflection to find out / call methods.
 *
 * by Szczepan Faber, created at: 12/9/11
 */
public class CompatibleIntrospector {

    private final Object target;

    public CompatibleIntrospector(Object target) {
        this.target = target;
    }

    private Method getMethod(String methodName) {
        try {
            return target.getClass().getDeclaredMethod(methodName, new Class[0]);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedMethodException("The method: " + methodName + " is not supported on instance: " + target + ".\n", e);
        }
    }

    public <T> T getSafely(T defaultValue, String methodName) {
        try {
            Method method = getMethod(methodName);
            method.setAccessible(true);
            return (T) method.invoke(target);
        } catch (UnsupportedMethodException e) {
            return defaultValue;
        } catch (Exception e) {
            throw new RuntimeException("Unable to get value reflectively", e);
        }
    }
}