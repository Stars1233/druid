/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexer;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.inject.Injector;
import org.apache.druid.guice.ExtensionsConfig;
import org.apache.druid.guice.ExtensionsLoader;
import org.apache.druid.guice.StartupInjectorBuilder;
import org.apache.druid.indexing.common.task.AbstractBatchIndexTask;
import org.apache.druid.indexing.common.task.Initialization;
import org.apache.druid.initialization.ServerInjectorBuilder;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.utils.JvmUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public abstract class HadoopTask extends AbstractBatchIndexTask
{
  private static final Logger log = new Logger(HadoopTask.class);

  static final Injector INJECTOR = new StartupInjectorBuilder()
      .forServer()
      .add(ServerInjectorBuilder.registerNodeRoleModule(ImmutableSet.of()))
      .build();
  private static final ExtensionsLoader EXTENSIONS_LOADER = ExtensionsLoader.instance(INJECTOR);

  private final List<String> hadoopDependencyCoordinates;
  private final HadoopTaskConfig hadoopTaskConfig;

  protected HadoopTask(
      String id,
      String dataSource,
      List<String> hadoopDependencyCoordinates,
      Map<String, Object> context,
      HadoopTaskConfig hadoopTaskConfig
  )
  {
    super(id, dataSource, context, IngestionMode.HADOOP);
    this.hadoopDependencyCoordinates = hadoopDependencyCoordinates;
    this.hadoopTaskConfig = hadoopTaskConfig;
  }

  public List<String> getHadoopDependencyCoordinates()
  {
    return hadoopDependencyCoordinates == null ? null : ImmutableList.copyOf(hadoopDependencyCoordinates);
  }

  // This could stand to have a more robust detection methodology.
  // Right now it just looks for `druid.*\.jar`
  // This is only used for classpath isolation in the runTask isolation stuff, so it shooouuullldddd be ok.
  /** {@link #buildClassLoader()} has outdated javadocs referencing this field, TODO update */
  @SuppressWarnings("unused")
  protected static final Predicate<URL> IS_DRUID_URL = new Predicate<>()
  {
    @Override
    public boolean apply(@Nullable URL input)
    {
      try {
        if (input == null) {
          return false;
        }
        final String fName = Paths.get(input.toURI()).getFileName().toString();
        return fName.startsWith("druid") && fName.endsWith(".jar") && !fName.endsWith("selfcontained.jar");
      }
      catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }
  };

  /**
   * This makes an isolated classloader that has classes loaded in the "proper" priority.
   *
   * This isolation is *only* for the part of the HadoopTask that calls runTask in an isolated manner.
   *
   * Jars for the job are the same jars as for the classloader EXCEPT the hadoopDependencyCoordinates, which are not
   * used in the job jars.
   *
   * The URLs in the resultant classloader are loaded in this priority:
   *
   * 1. Non-Druid jars (see {@link #IS_DRUID_URL}) found in the ClassLoader for HadoopIndexTask.class. This will
   * probably be the ApplicationClassLoader
   * 2. Hadoop jars found in the hadoop dependency coordinates directory, loaded in the order they are specified in
   * 3. Druid jars (see {@link #IS_DRUID_URL}) found in the ClassLoader for HadoopIndexTask.class
   * 4. Extension URLs maintaining the order specified in the extensions list in the extensions config
   *
   * At one point I tried making each one of these steps a URLClassLoader, but it is not easy to make a properly
   * predictive {@link #IS_DRUID_URL} that captures all things which reference druid classes. This lead to a case where
   * the class loader isolation worked great for stock druid, but failed in many common use cases including extension
   * jars on the classpath which were not listed in the extensions list.
   *
   * As such, the current approach is to make a list of URLs for a URLClassLoader based on the priority above, and use
   * THAT ClassLoader with a null parent as the isolated loader for running hadoop or hadoop-like driver tasks.
   * Such an approach combined with reasonable exclusions in org.apache.druid.cli.PullDependencies#exclusions tries to maintain
   * sanity in a ClassLoader where all jars (which are isolated by extension ClassLoaders in the Druid framework) are
   * jumbled together into one ClassLoader for Hadoop and Hadoop-like tasks (Spark for example).
   *
   * @return An isolated URLClassLoader not tied by parent chain to the ApplicationClassLoader
   */
  protected ClassLoader buildClassLoader()
  {
    return buildClassLoader(hadoopDependencyCoordinates, hadoopTaskConfig.getDefaultHadoopCoordinates());
  }

  public static ClassLoader buildClassLoader(final List<String> hadoopDependencyCoordinates,
                                             final List<String> defaultHadoopCoordinates)
  {
    final List<String> finalHadoopDependencyCoordinates = hadoopDependencyCoordinates != null
                                                          ? hadoopDependencyCoordinates
                                                          : defaultHadoopCoordinates;

    ClassLoader taskClassLoader = HadoopIndexTask.class.getClassLoader();
    final List<URL> jobURLs;
    if (taskClassLoader instanceof URLClassLoader) {
      // this only works with Java 8
      jobURLs = Lists.newArrayList(
          Arrays.asList(((URLClassLoader) taskClassLoader).getURLs())
      );
    } else {
      // fallback to parsing system classpath
      jobURLs = JvmUtils.systemClassPath();
    }

    final List<URL> extensionURLs = new ArrayList<>();
    for (final File extension : EXTENSIONS_LOADER.getExtensionFilesToLoad()) {
      final URLClassLoader extensionLoader = EXTENSIONS_LOADER.getClassLoaderForExtension(extension, false);
      extensionURLs.addAll(Arrays.asList(extensionLoader.getURLs()));
    }

    jobURLs.addAll(extensionURLs);

    final List<URL> localClassLoaderURLs = new ArrayList<>(jobURLs);

    // hadoop dependencies come before druid classes because some extensions depend on them
    for (final File hadoopDependency :
        Initialization.getHadoopDependencyFilesToLoad(
            finalHadoopDependencyCoordinates,
            EXTENSIONS_LOADER.config()
        )) {
      final URLClassLoader hadoopLoader = EXTENSIONS_LOADER.getClassLoaderForExtension(hadoopDependency, false);
      localClassLoaderURLs.addAll(Arrays.asList(hadoopLoader.getURLs()));
    }

    ClassLoader parent = ClassLoader.getPlatformClassLoader();
    final ClassLoader classLoader = new URLClassLoader(
        localClassLoaderURLs.toArray(new URL[0]),
        parent
    );

    final String hadoopContainerDruidClasspathJars;
    ExtensionsConfig extnConfig = EXTENSIONS_LOADER.config();
    if (extnConfig.getHadoopContainerDruidClasspath() == null) {
      hadoopContainerDruidClasspathJars = Joiner.on(File.pathSeparator).join(jobURLs);

    } else {
      List<URL> hadoopContainerURLs = Lists.newArrayList(
          ExtensionsLoader.getURLsForClasspath(extnConfig.getHadoopContainerDruidClasspath())
      );

      if (extnConfig.getAddExtensionsToHadoopContainer()) {
        hadoopContainerURLs.addAll(extensionURLs);
      }

      hadoopContainerDruidClasspathJars = Joiner.on(File.pathSeparator)
                                                .join(hadoopContainerURLs);
    }

    log.info("Hadoop Container Druid Classpath is set to [%s]", hadoopContainerDruidClasspathJars);
    System.setProperty("druid.hadoop.internal.classpath", hadoopContainerDruidClasspathJars);

    return classLoader;
  }

  /**
   * This method logs the {@link ExtensionsConfig} that was used to fetch the hadoop dependencies and build the classpath
   * for the jobs
   */
  protected static void logExtensionsConfig()
  {
    log.info("HadoopTask started with the following config:\n%s", EXTENSIONS_LOADER.config().toString());
  }

  /**
   * This method tries to isolate class loading during a Function call
   *
   * @param clazzName    The Class which has a static method called `runTask`
   * @param input        The input for `runTask`, must have `input.getClass()` be the class of the input for runTask
   * @param loader       The loader to use as the context class loader during invocation
   * @param <InputType>  The input type of the method.
   * @param <OutputType> The output type of the method. The result of runTask must be castable to this type.
   *
   * @return The result of the method invocation
   */
  public static <InputType, OutputType> OutputType invokeForeignLoader(
      final String clazzName,
      final InputType input,
      final ClassLoader loader
  )
  {
    log.debug("Launching [%s] on class loader [%s] with input class [%s]", clazzName, loader, input.getClass());
    final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(loader);
      final Class<?> clazz = loader.loadClass(clazzName);
      final Method method = clazz.getMethod("runTask", input.getClass());
      return (OutputType) method.invoke(null, input);
    }
    catch (IllegalAccessException | InvocationTargetException | ClassNotFoundException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
    finally {
      Thread.currentThread().setContextClassLoader(oldLoader);
    }
  }

  /**
   * This method tries to isolate class loading during a Function call
   *
   * @param clazzName    The Class which has an instance method called `runTask`
   * @param loader       The loader to use as the context class loader during invocation
   *
   * @return The result of the method invocation
   */
  public static Object getForeignClassloaderObject(
      final String clazzName,
      final ClassLoader loader
  )
  {
    log.debug("Launching [%s] on class loader [%s]", clazzName, loader);
    final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(loader);
      final Class<?> clazz = loader.loadClass(clazzName);
      return clazz.newInstance();
    }
    catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    finally {
      Thread.currentThread().setContextClassLoader(oldLoader);
    }
  }
}
