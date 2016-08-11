/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.plugins.groovy.extensions;

import com.intellij.openapi.extensions.AbstractExtensionPointBean;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.util.xmlb.annotations.AbstractCollection;
import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Property;

/**
 * @author Sergey Evdokimov
 */
public class GroovyClassDescriptor extends AbstractExtensionPointBean {

  public static final ExtensionPointName<GroovyClassDescriptor> EP_NAME = new ExtensionPointName<>("org.intellij.groovy.classDescriptor");

  @Attribute("class")
  //@Required
  public String className;

  @Property(surroundWithTag = false)
  @AbstractCollection(surroundWithTag = false)
  public GroovyMethodDescriptorTag[] methods;

}
