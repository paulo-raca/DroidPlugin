/*
**        DroidPlugin Project
**
** Copyright(c) 2015 Andy Zhang <zhangyong232@gmail.com>
**
** This file is part of DroidPlugin.
**
** DroidPlugin is free software: you can redistribute it and/or
** modify it under the terms of the GNU Lesser General Public
** License as published by the Free Software Foundation, either
** version 3 of the License, or (at your option) any later version.
**
** DroidPlugin is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
** Lesser General Public License for more details.
**
** You should have received a copy of the GNU Lesser General Public
** License along with DroidPlugin.  If not, see <http://www.gnu.org/licenses/lgpl.txt>
**
**/

package com.morgoo.droidplugin.core;

import android.os.Build;

import com.morgoo.helper.Log;

import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Created by Andy Zhang(zhangyong232@gmail.com) on 2015/2/4.
 */
public class PluginClassLoader extends DexClassLoader {

    public PluginClassLoader(String apkfile, String optimizedDirectory, String libraryPath, ClassLoader systemClassLoader) {
        super(apkfile, optimizedDirectory, libraryPath, systemClassLoader);
    }

    private static final List<String> sPreLoader = new ArrayList<>();

    static {
        sPreLoader.add("QIKU");
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {

        if (Build.MANUFACTURER != null && sPreLoader.contains(Build.MANUFACTURER.toUpperCase())) {
            try {
                /**
                 * FUCK QIKU!
                 * This adapter odd cool youth version of the phone.
                 * Because the odd cool phone loaded their own modified the Support V4 libraries in the plug-in is also used when the library, ClassLoader will give priority to load the odd cool phone comes with Support V4 library.
                 * The reason is that the odd cool phone is not pre-loaded plug-in play Support V4 library. Details can be studied super.loadClass (className, resolve) standard implementation
                 * However, this may result in the class are not compatible, there java.lang.IncompatibleClassChangeError. Because the plug-in was compiled with plug Support V4, while the odd cool phone makes
                 * Using its modified Support V4.
                 *
                 * SO, in Class Loader to load a Class, we loaded first Class from its own ClassLoader, and if not, then go from Parent Class Loader loaded.
                 * After this modification, Class load order system just it is not the same.
                 *
                 */
                Class<?> clazz = findClass(className);
                if (clazz != null) {
                    return clazz;
                }
            } catch (ClassNotFoundException e) {
                Log.e("PluginClassLoader", "UCK QIKU:error", e);
            }
        }
        return super.loadClass(className, resolve);
    }
}
