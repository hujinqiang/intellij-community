// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.AbstractExtensionPointBean;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Map;
import java.util.ResourceBundle;

public abstract class DynamicBundle extends AbstractBundle {
  private final static Logger LOG = Logger.getInstance(DynamicBundle.class);
  private final static Method SET_PARENT = ReflectionUtil.getDeclaredMethod(ResourceBundle.class, "setParent", ResourceBundle.class);

  protected DynamicBundle(@NotNull String pathToBundle) {
    super(pathToBundle);
  }

  // see BundleUtil
  @Override
  protected ResourceBundle findBundle(@NotNull String pathToBundle,
                                      @NotNull ClassLoader baseLoader,
                                      @NotNull ResourceBundle.Control control) {
    ResourceBundle base = super.findBundle(pathToBundle, baseLoader, control);

    if (DefaultBundleService.isDefaultBundle()) {
      return base;
    }

    LanguageBundleEP langBundle = findLanguageBundle();
    if (langBundle == null) return base;

    ResourceBundle pluginBundle = super.findBundle(pathToBundle, langBundle.getLoaderForClass(), control);
    if (pluginBundle == null) return base;

    try {
      if (SET_PARENT != null) {
        SET_PARENT.invoke(pluginBundle, base);
      }
    }
    catch (Exception e) {
      LOG.warn(e);
      return base;
    }
    return pluginBundle;
  }

  // todo: one language per application
  @Nullable
  private static LanguageBundleEP findLanguageBundle() {
    try {
      Application application = ApplicationManager.getApplication();
      if (application == null) return null;
      if (application.isUnitTestMode() && !application.getExtensionArea().hasExtensionPoint(LanguageBundleEP.EP_NAME)) {
        return null;
      }
      return LanguageBundleEP.EP_NAME.findExtension(LanguageBundleEP.class);
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Exception e) {
      LOG.error(e);
      return null;
    }
  }

  public static final DynamicBundle INSTANCE = new DynamicBundle("") {
  };

  public static class LanguageBundleEP extends AbstractExtensionPointBean {
    public static final ExtensionPointName<LanguageBundleEP> EP_NAME = ExtensionPointName.create("com.intellij.languageBundle");
  }

  private static final Map<String, DynamicBundle> ourBundlesForForms = ContainerUtil.createConcurrentSoftValueMap();

  /**
   * @deprecated used only dy GUI form builder
   */
  @Deprecated
  public static ResourceBundle getBundle(@NotNull String baseName) {
    Class<?> callerClass = ReflectionUtil.findCallerClass(2);
    return getBundle(baseName, callerClass == null ? DynamicBundle.class : callerClass);
  }

  /**
   * @deprecated used only dy GUI form builder
   */
  @Deprecated
  public static ResourceBundle getBundle(@NotNull String baseName, @NotNull Class<?> formClass) {
    DynamicBundle dynamic = ourBundlesForForms.computeIfAbsent(baseName, s -> new DynamicBundle(s) {});
    ResourceBundle rb = dynamic.getResourceBundle(formClass.getClassLoader());

    if (BundleBase.SHOW_LOCALIZED_MESSAGES) {
      return new ResourceBundle() {
        @Override
        protected Object handleGetObject(@NotNull String key) {
          Object get = rb.getObject(key);
          assert get instanceof String : "Language bundles should contain only strings";
          return BundleBase.appendLocalizationMarker((String)get);
        }

        @NotNull
        @Override
        public Enumeration<String> getKeys() {
          return rb.getKeys();
        }
      };
    }
    return rb;
  }
}