package cn.loveq.podcast.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 启动自动续播的走法。
 *
 * 关键是第二条：服务里**已经有队列**时不能重建 —— 用户按返回键退出界面但音乐还在放，
 * 再点开应用时重建队列会把播放位置退回上一次落盘点，听着就是「节目自己往回跳」。
 */
class LaunchResumeTest {

    @Test
    fun `服务里没有内容——按上次收听的那期起播`() {
        assertEquals(
            LaunchResume.StartFresh,
            launchResumeAction(playRequested = false, hasQueue = false)
        )
    }

    @Test
    fun `服务里已经有队列——只接着播，绝不重建`() {
        assertEquals(
            LaunchResume.Continue,
            launchResumeAction(playRequested = false, hasQueue = true)
        )
    }

    @Test
    fun `用户已经点过播——自动行为让开`() {
        assertEquals(LaunchResume.Skip, launchResumeAction(playRequested = true, hasQueue = false))
        assertEquals(LaunchResume.Skip, launchResumeAction(playRequested = true, hasQueue = true))
    }
}
