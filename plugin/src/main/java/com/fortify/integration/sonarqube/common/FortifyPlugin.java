/*******************************************************************************
 * (c) Copyright 2017 EntIT Software LLC, a Micro Focus company
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a 
 * copy of this software and associated documentation files (the 
 * "Software"), to deal in the Software without restriction, including without 
 * limitation the rights to use, copy, modify, merge, publish, distribute, 
 * sublicense, and/or sell copies of the Software, and to permit persons to 
 * whom the Software is furnished to do so, subject to the following 
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included 
 * in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY 
 * KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE 
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR 
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN 
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS 
 * IN THE SOFTWARE.
 ******************************************************************************/
package com.fortify.integration.sonarqube.common;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.sonar.api.Plugin;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import com.fortify.integration.sonarqube.common.source.fod.FortifyCommonFoDExtensionProvider;
import com.fortify.integration.sonarqube.common.source.ssc.FortifyCommonSSCExtensionProvider;

/**
 * This main plugin class provides the following functionality:
 * <ul>
 *   <li>Define common SonarQube extension points that are not specific to a specific SonarQube API version.</li>
 *   <li>Based on the SonarQube version that that the plugin is running under, load the most suitable
 *       {@link IFortifyExtensionProvider} implementation.</li>
 *   <li>Get all version-specific SonarQube extension points from the {@link IFortifyExtensionProvider}.</li>
 *   <li>Register both common and version-specific extension points as SonarQube extensions.</li>
 *   <li>Invoke the static <code>addPropertyDefinitions()</code> method (if defined) on each extension point.</li>
 *   <li>Add the {@link PropertyDefinition} instances as generated by the <code>addPropertyDefinitions()</code>
 *       methods as SonarQube extension points.</li>
 * </ul>
 */
public class FortifyPlugin implements Plugin {
	private static final Logger LOG = Loggers.get(FortifyPlugin.class);
	private static final IFortifyExtensionProvider[] EXTENSION_PROVIDERS_COMMON = {
		new FortifyCommonExtensionProvider(),
		new FortifyCommonSSCExtensionProvider(),
		new FortifyCommonFoDExtensionProvider()
	};
	private static final String[] EXTENSION_PROVIDER_CLASS_NAMES_67 = {
		"com.fortify.integration.sonarqube.sq67.source.ssc.FortifySSCSQ67ExtensionProvider",
		//"com.fortify.integration.sonarqube.sq76.source.fod.FortifyFoDSQ76ExtensionProvider"
	};
	private static final String[] EXTENSION_PROVIDER_CLASS_NAMES_76 = {
		"com.fortify.integration.sonarqube.sq76.source.ssc.FortifySSCSQ76ExtensionProvider",
		"com.fortify.integration.sonarqube.sq76.source.fod.FortifyFoDSQ76ExtensionProvider"
	};

	/**
	 * Define this plugin by calling the {@link #addExtensions(org.sonar.api.Plugin.Context, Class[])}
	 * method for both version-specific extensions as returned by the {@link #getExtensionProvider(org.sonar.api.Plugin.Context)}
	 * method, and common extensions as defined by {@link #COMMON_EXTENSIONS}.
	 */
	@Override
	public void define(Context context) {
		for ( IFortifyExtensionProvider extensionProvider : EXTENSION_PROVIDERS_COMMON ) {
			addExtensions(context, extensionProvider.getExtensions(context));
		}
		for ( IFortifyExtensionProvider extensionProvider : getVersionSpecificExtensionProviders(context) ) {
			addExtensions(context, extensionProvider.getExtensions(context));
		}
	}

	/**
	 * This method registers each of the given extensions as a SonarQube extension,
	 * as well as the corresponding {@link PropertyDefinition} instances as generated
	 * by the {@link #invokePropertyDefinitionsMethod(Class, List)} method.
	 * @param context
	 * @param extensions
	 */
	private void addExtensions(Context context, Class<?>[] extensions) {
		List<PropertyDefinition> propertyDefinitions = new ArrayList<>();
		for (Class<?> extension : extensions) {
			LOG.info("Loading extension "+extension.getName());
			context.addExtension(extension);
			invokePropertyDefinitionsMethod(extension, propertyDefinitions);
		}
		context.addExtensions(propertyDefinitions);
	}

	/**
	 * This method loads the most applicable {@link IFortifyExtensionProvider}
	 * implementation based on the SonarQube version that the plugin is running under.
	 * @param context
	 * @return
	 */
	private Set<IFortifyExtensionProvider> getVersionSpecificExtensionProviders(Context context) {
		int major = context.getSonarQubeVersion().major();
		int minor = context.getSonarQubeVersion().minor();
		String[] extensionProviderClassNames = 
				(major==7 && minor>= 6) || major>7 ? EXTENSION_PROVIDER_CLASS_NAMES_76 : EXTENSION_PROVIDER_CLASS_NAMES_67;
		
		Set<IFortifyExtensionProvider> result = new LinkedHashSet<>();
		for ( String extensionProviderClassName : extensionProviderClassNames ) {
			try {
				result.add((IFortifyExtensionProvider)Class.forName(extensionProviderClassName).newInstance());
			} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
				throw new RuntimeException("Error instantiating "+extensionProviderClassName, e);
			}
		}
		return result;
	}

	/**
	 * This method invokes the static, optional <code>addPropertyDefinitions()</code> method 
	 * on the given type and all of its super-classes to collect the {@link PropertyDefinition} 
	 * instances for each extension point. 
	 * @param type
	 * @param propertyDefinitions
	 */
	private static final void invokePropertyDefinitionsMethod(Class<?> type, List<PropertyDefinition> propertyDefinitions) {
		while ( !type.equals(Object.class) ) {
			try {
				Method method = type.getDeclaredMethod("addPropertyDefinitions", List.class);
				if ((method.getModifiers() & Modifier.STATIC) != 0) {
					method.invoke(null, propertyDefinitions);
				}
			} catch (NoSuchMethodException e) {
				// Expected exception, as not all extension classes define the addPropertyDefinitions method
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				throw new RuntimeException("Error adding property definitions from "+type.getName(), e);
			}
			type = type.getSuperclass();
		}
	}

}
