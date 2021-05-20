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

package net.flintmc.launcher.util;

import net.flintmc.launcher.classloading.ChildClassLoader;
import net.flintmc.launcher.service.LauncherPlugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.*;

/**
 * Helper enumeration implementation for composing multiple class paths of different class loaders
 * into a single resource search enumeration.
 */
public class ClassPathEnumeration implements Enumeration<URL> {

  private final String resourceName;

  private Enumeration<URL> currentEnumeration;
  private final Iterator<ChildClassLoader> classLoaders;
  private final Set<LauncherPlugin> resourcePathManipulators;

  private URL currentURL;

  /**
   * Constructs and initializes a new class path search.
   *
   * @param resourceName             The name of the resource to search
   * @param startEnumeration         The initial enumeration to search
   * @param classLoaders             Additional class loaders to search
   * @param resourcePathManipulators The launcher plugins to call for manipulating the resource
   *                                 path, or {@code null}, if no resource path manipulations should
   *                                 be performed
   */
  public ClassPathEnumeration(
      String resourceName,
      Enumeration<URL> startEnumeration,
      List<ChildClassLoader> classLoaders,
      Set<LauncherPlugin> resourcePathManipulators) {
    this.resourceName = resourceName;
    this.currentEnumeration = startEnumeration;
    this.classLoaders = classLoaders.iterator();
    this.resourcePathManipulators = resourcePathManipulators;
  }

  private boolean next() throws IOException {
    if (this.currentURL != null) {
      // We already have a custom URL, no need to go to the next one
      return true;
    }

    while (!this.currentEnumeration.hasMoreElements()) {
      if (!this.classLoaders.hasNext()) {
        // The current enumeration has no more elements, and there are no class loaders left
        return false;
      }

      // The current enumeration has no matching elements, query the next class loader
      this.currentEnumeration = this.classLoaders.next().commonFindResources(resourceName);
    }

    // The current enumeration has matching element(s), retrieve the next one
    this.currentURL = this.currentEnumeration.nextElement();

    if (this.resourcePathManipulators != null) {
      // Let each launcher plugin manipulate the resource paths
      for (LauncherPlugin manipulator : this.resourcePathManipulators) {
        URL newURL = manipulator.adjustResourceURL(resourceName, this.currentURL);

        if (newURL != null) {
          // Path has been overwritten
          this.currentURL = newURL;
        }
      }
    }

    return true;
  }

  @Override
  public boolean hasMoreElements() {
    try {
      return next();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public URL nextElement() {
    try {
      if (!next()) {
        throw new NoSuchElementException();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    // Take the current URL and clear it internally, so that the next() function will search again
    URL tmp = this.currentURL;
    this.currentURL = null;
    return tmp;
  }
}
