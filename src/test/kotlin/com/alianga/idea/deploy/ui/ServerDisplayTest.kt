package com.alianga.idea.deploy.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class ServerDisplayTest {
    @Test
    fun `id 与名称相同只显示一次`() = assertEquals("164", ServerDisplay.title("164", "164"))

    @Test
    fun `名称为空只显示 id`() = assertEquals("164", ServerDisplay.title("164", " "))

    @Test
    fun `id 与名称不同用中点连接`() = assertEquals("233 · 国开", ServerDisplay.title("233", "国开"))

    @Test
    fun `地址格式`() = assertEquals("root@1.2.3.4:22", ServerDisplay.address("root", "1.2.3.4", 22))
}
