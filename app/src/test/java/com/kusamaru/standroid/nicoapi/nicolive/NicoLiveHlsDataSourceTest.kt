package com.kusamaru.standroid.nicoapi.nicolive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合成データ。実際のstreamメッセージの形（data.uri / data.cookies）に合わせている */
class NicoLiveHlsDataSourceTest {

    private val streamMessage = """
        {"type":"stream","data":{
          "uri":"https://delivery.domand.nicovideo.jp/hlslive/v1/delivery/abc/master.m3u8",
          "quality":"abr","availableQualities":["abr","super_high","high"],"protocol":"hls",
          "cookies":[
            {"domain":".domand.nicovideo.jp","path":"/","name":"domand_bid","value":"token123","secure":true,"expires":"2026-10-18T00:00:00+09:00"},
            {"domain":"other.example.com","path":"/","name":"x","value":"y","secure":true}
          ]}}
    """.trimIndent()

    @Test
    fun parseCookies_streamMessage() {
        val cookies = NicoLiveHlsDataSource.parseCookies(streamMessage)
        assertEquals(2, cookies.size)
        assertEquals("domand_bid", cookies[0].name)
        assertEquals("token123", cookies[0].value)
    }

    @Test
    fun parseCookies_oldFormatWithoutCookies() {
        val old = """{"type":"stream","data":{"uri":"https://example.com/master.m3u8?ht2_nicolive=xxx"}}"""
        assertTrue(NicoLiveHlsDataSource.parseCookies(old).isEmpty())
        assertTrue(NicoLiveHlsDataSource.parseCookies(null).isEmpty())
    }

    @Test
    fun cookieHeader_onlyForMatchingDomain() {
        val cookies = NicoLiveHlsDataSource.parseCookies(streamMessage)
        assertEquals("domand_bid=token123", NicoLiveHlsDataSource.cookieHeaderFor(cookies, "delivery.domand.nicovideo.jp", "/hlslive/a.m3u8"))
        assertEquals("domand_bid=token123", NicoLiveHlsDataSource.cookieHeaderFor(cookies, "domand.nicovideo.jp", "/"))
        assertNull(NicoLiveHlsDataSource.cookieHeaderFor(cookies, "evil-domand.nicovideo.jp.example.net", "/"))
        assertNull(NicoLiveHlsDataSource.cookieHeaderFor(cookies, "www.nicovideo.jp", "/"))
    }
}
