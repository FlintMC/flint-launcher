/*
 * FlintMC
 * Copyright (C) 2020-2021 LabyMedia GmbH and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package net.flintmc.launcher;

import com.beust.jcommander.JCommander;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import net.flintmc.launcher.classloading.RootClassLoader;
import net.flintmc.launcher.service.LauncherPlugin;
import net.flintmc.launcher.service.PreLaunchException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main API system for the Launcher
 */
public class LaunchController {

  private static LaunchController instance;
  private final Logger logger;
  private final RootClassLoader rootLoader;
  private final List<String> commandLine;
  private final LaunchArguments launchArguments;

  /**
   * Constructs a new {@link LaunchController} instance and sets it as the active one.
   *
   * @param rootLoader  Class loader to use for loading
   * @param commandLine Commandline arguments to pass in
   * @throws IllegalStateException If a {@link LaunchController} instance has been created already
   */
  public LaunchController(RootClassLoader rootLoader, String[] commandLine) {
    if (instance != null) {
      throw new IllegalStateException(
          "The launcher cannot be instantiated twice in the same environment");
    }

    instance = this;
    this.rootLoader = rootLoader;
    this.logger = LogManager.getLogger(LaunchController.class);
    this.commandLine = new ArrayList<>(Arrays.asList(commandLine));
    this.launchArguments = new LaunchArguments();
  }

  /**
   * Retrieves the instance of the launcher the program has been launched with.
   *
   * @return Instance of the launcher or null if the program has not been launched with this
   * launcher
   */
  public static LaunchController getInstance() {
    return instance;
  }

  /**
   * Executes the launch. This is effectively the new `main` method.
   *
   * <p><b>Called by the {@link FlintLauncher} using reflection</b>
   */
  public void run() {
    logger.info("Initializing LaunchController");
    logger.info("Java version: {}", System.getProperty("java.version"));
    logger.info(
        "Operating System: {} {}", System.getProperty("os.name"), System.getProperty("os.version"));
    logger.info("JVM vendor: {}", System.getProperty("java.vendor"));

    // Find the first set of plugins by searching the classpath
    logger.trace("About to load plugins");
    ServiceLoader<LauncherPlugin> serviceLoader =
        ServiceLoader.load(LauncherPlugin.class, rootLoader);

    List<LauncherPlugin> plugins = new ArrayList<>();
    serviceLoader.forEach(plugins::add);
    logger.info(
        "Loaded {} initial {}.", plugins.size(), plugins.size() != 1 ? "plugins" : "plugin");
    rootLoader.addPlugins(plugins);

    // Loading pass control flow:
    // 1. Let plugins register their commandline receivers
    // 2. Let plugins modify the current commandline for the first time
    // 3. Parse the commandline
    // -- From now on, the plugins may have been configured via the commandline
    // 4. Let plugins modify the current commandline for the second time
    // 5. Prepare the root loader for each plugin (e.g. add more URLs to its classpath)
    // 6. Let plugins add extra plugins
    // 7. Repeat 1 - 6 for every newly added extra plugin until no plugin has got more extra plugins

    Set<Object> commandlineArguments = new HashSet<>();
    commandlineArguments.add(launchArguments);

    for (LauncherPlugin plugin : plugins) {
      // 1. - 4.
      this.initializePlugin(plugin, commandlineArguments);
    }

    // 5.
    rootLoader.addPlugins(plugins);
    rootLoader.prepare();

    int loadingPasses = 0;

    List<LauncherPlugin> initialPlugins = new ArrayList<>(plugins);
    for (LauncherPlugin plugin : initialPlugins) {
      // 6.
      int pluginLoadingPasses = this.loadExtraPlugins(plugins, commandlineArguments, plugin);
      loadingPasses = Math.max(loadingPasses, pluginLoadingPasses);
    }

    // Registering and calling all plugins is done, continue with launch
    logger.info(
        "Took {} loading {} to initialize system, loaded {} {}",
        loadingPasses,
        loadingPasses != 1 ? "passes" : "pass",
        plugins.size(),
        plugins.size() != 1 ? "plugins" : "plugin");

    logger.trace(
        "Loaded plugins: {}",
        plugins.stream().map(LauncherPlugin::name).collect(Collectors.joining(", ")));

    // Prepare to hand control over to the launch target
    logger.info("Handing over to launch target {}", launchArguments.getLaunchTarget());

    LauncherPlugin exceptionContext = null;

    try {
      // Run final plugin operation within the new launch environment
      for (LauncherPlugin plugin : plugins) {
        exceptionContext = plugin;
        plugin.preLaunch(rootLoader);
      }

      String launchTargetName = launchArguments.getLaunchTarget();

      if (launchTargetName == null || launchTargetName.isEmpty()) {
        throw new IllegalStateException(
            "No launch target found (set a launch target using --launch-target or use a package which specifies one)");
      }

      Class<?> launchTarget = rootLoader.loadClass(launchTargetName);

      Method mainMethod = launchTarget.getMethod("main", String[].class);
      mainMethod.invoke(null, (Object) launchArguments.getOtherArguments().toArray(new String[0]));
    } catch (ClassNotFoundException exception) {
      logger.fatal("Failed to find launch target class", exception);
      System.exit(1);
    } catch (NoSuchMethodException exception) {
      logger.fatal("Launch target has no main method", exception);
      System.exit(1);
    } catch (InvocationTargetException exception) {
      logger.fatal("Invoking main method threw an error", exception);
      System.exit(1);
    } catch (IllegalAccessException exception) {
      logger.fatal("Unable to invoke main method due to missing access", exception);
      System.exit(1);
    } catch (PreLaunchException exception) {
      logger.fatal("Exception while invoking pre-launch callback: {}", exceptionContext, exception);
      System.exit(1);
    }
  }

  private void initializePlugin(LauncherPlugin plugin, Set<Object> commandlineArguments) {
    // 1.
    plugin.adjustLoadCommandlineArguments(commandlineArguments);

    // 2.
    plugin.modifyCommandlineArguments(commandLine);

    // 3.
    JCommander commandlineParser = new JCommander();
    commandlineParser.addObject(commandlineArguments);
    commandlineParser.parse(commandLine.toArray(new String[0]));

    // 4.
    plugin.modifyCommandlineArguments(commandLine);
  }

  private int loadExtraPlugins(
      List<LauncherPlugin> plugins, Set<Object> commandlineArguments, LauncherPlugin plugin) {
    int loadingPasses = 1;

    List<LauncherPlugin> currentPlugins = new ArrayList<>();
    currentPlugins.add(plugin);

    do {
      List<LauncherPlugin> initializingPlugins = new ArrayList<>(currentPlugins);
      currentPlugins.clear();

      for (LauncherPlugin currentPlugin : initializingPlugins) {
        List<LauncherPlugin> extraPlugins = new ArrayList<>();

        try {
          extraPlugins.addAll(currentPlugin.extraPlugins());
        } catch (ClassNotFoundException exception) {
          this.logger.warn(
              "Failed to load a class of an extra plugin from " + currentPlugin.name(), exception);
        }

        plugins.addAll(extraPlugins);

        for (LauncherPlugin extraPlugin : extraPlugins) {
          // 1. - 4.
          this.initializePlugin(extraPlugin, commandlineArguments);
        }

        this.rootLoader.addPlugins(extraPlugins);

        for (LauncherPlugin extraPlugin : extraPlugins) {
          // 5.
          extraPlugin.configureRootLoader(rootLoader);
        }

        for (LauncherPlugin extraPlugin : extraPlugins) {
          // 7.
          loadingPasses += this.loadExtraPlugins(currentPlugins, commandlineArguments, extraPlugin);
        }
      }
    } while (!currentPlugins.isEmpty());

    return loadingPasses;
  }

  /**
   * Retrieves the class loader used by this launch controller for loading application classes
   *
   * @return Class loader used by this launch controller
   */
  public RootClassLoader getRootLoader() {
    return rootLoader;
  }
}
