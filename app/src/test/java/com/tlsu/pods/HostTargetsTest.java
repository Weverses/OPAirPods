// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HostTargetsTest {
    @Test public void obfuscatedTargetsUseDexKitOnly() {
        for (String key : new String[]{
                HostTargets.UI_SUBPAGE_CLASS,
                HostTargets.UI_CAPSULE_PRODUCER_CLASS,
                HostTargets.UI_CAPSULE_PRODUCER_PLAIN,
                HostTargets.UI_CAPSULE_PRODUCER_INIT,
                HostTargets.UI_DEVICE_INFO, HostTargets.UI_MODEL_MAX, HostTargets.UI_MODEL_PRO,
                HostTargets.UI_CAPSULE_START, HostTargets.UI_CAPSULE_END, HostTargets.UI_CONNECTION_UPDATE,
                HostTargets.UI_PREF_ADD, HostTargets.UI_PREF_FIND, HostTargets.UI_PREF_SUMMARY, HostTargets.UI_PREF_KEY, HostTargets.UI_PREF_TITLE,
                HostTargets.UI_PREF_CHILD, HostTargets.UI_PREF_COUNT, HostTargets.UI_PREF_ORDER, HostTargets.UI_PREF_REMOVE,
                PreferenceTargets.PERSISTENT, PreferenceTargets.VISIBLE, PreferenceTargets.ENABLED,
                PreferenceTargets.SELECTABLE, PreferenceTargets.CHECKED, PreferenceTargets.NOTIFY,
                PreferenceTargets.PARENT, PreferenceTargets.CLICK, PreferenceTargets.CHANGE,
                PreferenceTargets.ENTRIES, PreferenceTargets.VALUES, PreferenceTargets.DIALOG_TITLE,
                NavigationTargets.ATTACH, NavigationTargets.FRAGMENTS, NavigationTargets.SAVED,
                NavigationTargets.BEGIN, NavigationTargets.ANIMATIONS, NavigationTargets.HIDE,
                NavigationTargets.ADD, NavigationTargets.BACKSTACK, NavigationTargets.COMMIT, NavigationTargets.POP,
                CapsuleTargets.BASE, CapsuleTargets.APPLICATION, CapsuleTargets.REGISTRY, CapsuleTargets.ROOT,
                CapsuleTargets.GET, CapsuleTargets.KCLASS, CapsuleTargets.CURRENT, CapsuleTargets.UTILS}) {
            assertTrue(key, HostTargets.hasFingerprint(key));
            assertFalse(key, HostTargets.isExactFirst(key));
        }
    }

    @Test public void namedTargetsUseDirectLookupOnly() {
        for (String key : HostTargets.registeredKeys()) {
            if (HostTargets.hasFingerprint(key)) {
                assertFalse(key, HostTargets.isExactFirst(key));
                continue;
            }
            assertTrue(key, HostTargets.isExactFirst(key));
            assertFalse(key, HostTargets.hasFingerprint(key));
        }
    }
    @Test public void hostCallsDoNotEmbedObfuscatedNames() throws Exception {
        var root=java.nio.file.Path.of("src/main/java/com/tlsu/pods");
        var shortReflection=java.util.regex.Pattern.compile(
                "Reflect\\.(?:call|method|field)\\([^;\\n]*?,\\s*\"[A-Za-z][0-9]?\"");
        try(var files=java.nio.file.Files.list(root)) {
            for(var path:(Iterable<java.nio.file.Path>)files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source=new String(java.nio.file.Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(path.toString(),shortReflection.matcher(source).find());
                assertFalse(path.toString(),source.contains("Class.forName(\"aa."));
                assertFalse(path.toString(),source.contains("searchPackages(\"aa\")"));
            }
        }
    }

}
