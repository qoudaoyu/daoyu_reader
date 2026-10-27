package com.daoyu.tingshu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Offline: how model answers and the rule fallback map onto speakers (no line of a known character becomes 路人). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DramaAttributionTest {
    private val body = "林墨抬起头：“我不退。”\n柳如烟冷笑：“由不得你。”\n“那就试试。”\n“你配吗？”\n“配不配，三日后便知。”"
    private val cast = """{"name":"林墨","aliases":[],"gender":"男","age":"少年","voice":""},{"name":"柳如烟","aliases":["如烟"],"gender":"女","age":"少年","voice":""}"""

    @Test fun shortAndDecoratedNames() = runBlocking {
        val d = App.instance.director
        // the model writes "如烟（柳家小姐）" and "林墨少爷", and leaves Q4 out
        d.fakeLlmForTest = { """{"characters":[$cast],"lines":[[0,"我不退","林墨少爷","",0],[1,"由不得你","如烟（柳家小姐）","",1],[2,"那就试试","林墨","",1],[3,"你配吗","柳如烟","",2]]}""" }
        try {
            val lines = d.analyseForTest(9_100_001L, body)
            assertEquals(listOf("林墨", "柳如烟", "林墨", "柳如烟", "林墨"), lines.map { it.speaker })
        } finally { d.fakeLlmForTest = null }
    }

    @Test fun rulesUseColonsAndTurns() = runBlocking {
        val d = App.instance.director
        d.fakeLlmForTest = { """{"characters":[$cast],"lines":[]}""" }
        try {
            d.analyseForTest(9_100_002L, body)   // registers the cast; every line then goes to the rules
            assertEquals(listOf("林墨", "柳如烟", "林墨", "柳如烟", "林墨"), d.ruleBasedForTest(9_100_002L, body).map { it.speaker })
            // narration between the quotes breaks the back-and-forth: an unknown line stays 路人
            val other = "林墨抬起头：“我不退。”\n柳如烟冷笑：“由不得你。”\n门外有人喊了一声。\n“开门！”"
            assertEquals("路人", d.ruleBasedForTest(9_100_002L, other).last().speaker)
        } finally { d.fakeLlmForTest = null }
    }

    @Test fun missingSpeakerColumnIsAskedAgain() = runBlocking {
        val d = App.instance.director
        var calls = 0
        // first answer drops the speaker column ([id, head, tone, level]), as qwen3.8-max sometimes does
        d.fakeLlmForTest = {
            if (calls++ == 0) """{"characters":[$cast],"lines":[[0,"我不退","坚决",1],[1,"由不得你","冷笑",1],[2,"那就试试","",1],[3,"你配吗","",2],[4,"配不配","",1]]}"""
            else """{"characters":[$cast],"lines":[[0,"我不退","林墨","",1],[1,"由不得你","柳如烟","",1],[2,"那就试试","林墨","",1],[3,"你配吗","柳如烟","",2],[4,"配不配","林墨","",1]]}"""
        }
        try {
            assertEquals(listOf("林墨", "柳如烟", "林墨", "柳如烟", "林墨"), d.analyseForTest(9_100_003L, body).map { it.speaker })
            assertEquals(2, calls)
        } finally { d.fakeLlmForTest = null }
    }

    @Test fun garbledTwiceFallsBackToRules() = runBlocking {
        val d = App.instance.director
        d.fakeLlmForTest = { """{"characters":[$cast],"lines":[[0,"我不退","坚决",1],[1,"由不得你","冷笑",1],[2,"那就试试","",1],[3,"你配吗","",2],[4,"配不配","",1]]}""" }
        try {
            assertEquals(listOf("林墨", "柳如烟", "林墨", "柳如烟", "林墨"), d.analyseForTest(9_100_004L, body).map { it.speaker })
        } finally { d.fakeLlmForTest = null }
    }
}
