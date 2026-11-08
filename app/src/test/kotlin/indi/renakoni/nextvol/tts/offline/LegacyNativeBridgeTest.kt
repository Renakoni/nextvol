package indi.renakoni.nextvol.tts.offline

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class LegacyNativeBridgeTest {
    @Test fun vcsCanResolveTheTaskFactoryCalledFromItsAudioThread() {
        val adapter = Class.forName("com.autonavi.jni.tts.AudioManagerAdapter", false, javaClass.classLoader)
        val method = adapter.getDeclaredMethod("generateTask")
        assertEquals("com.autonavi.jni.tts.AudioPlayerTask", method.returnType.name)
        assertFalse(Modifier.isStatic(method.modifiers))
    }

    @Test fun sogouCanResolveItsStaticLogCallbackEvenWhenLoggingIsDisabled() {
        val logger = Class.forName("com.sogou.speech.tts.util.LogUtil", false, javaClass.classLoader)
        val method = logger.getDeclaredMethod("sendLog", Int::class.javaPrimitiveType, String::class.java, String::class.java)
        assertEquals(Void.TYPE, method.returnType)
        assertTrue(Modifier.isStatic(method.modifiers))
    }
}
