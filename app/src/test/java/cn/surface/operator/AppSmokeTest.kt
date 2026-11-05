package cn.surface.operator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import cn.surface.operator.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    @Test fun launchesDisconnectedAndDemoLifecycleStopsManual() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get()
        val vm=ViewModelProvider(activity)[OperatorViewModel::class.java]
        try {
            assertFalse(vm.state.value.connected)
            vm.connect("",true)
            repeat(20) {Thread.sleep(50);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))}
            assertTrue(vm.state.value.connected)
            assertTrue(vm.state.value.demo)
            assertNotNull(vm.state.value.map)
            vm.page(Page.MANUAL);vm.armManual();vm.direction("FORWARD")
            assertTrue(vm.state.value.manual.active)
            controller.pause()
            assertFalse(vm.state.value.manual.active)
            assertFalse(vm.state.value.driving)
            controller.resume()
            assertFalse(vm.state.value.driving)
            vm.page(Page.TASK)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            val view=activity.window.decorView
            fun capture(name:String,width:Int=2560,height:Int=1600) {
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height)
                // A responsive layout switch creates a new MapPanel bitmap producer.
                repeat(4) {Thread.sleep(30);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))}
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height);view.draw(Canvas(bitmap))
                val output=File("build/reports/screenshots/$name.png");output.parentFile?.mkdirs()
                output.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            capture("tablet-landscape")
            vm.page(Page.MANUAL);vm.armManual()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            capture("tablet-manual")
            vm.page(Page.TASK)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            capture("tablet-portrait",1600,2560)
        } finally {vm.disconnect();controller.pause().stop().destroy()}
    }
}
