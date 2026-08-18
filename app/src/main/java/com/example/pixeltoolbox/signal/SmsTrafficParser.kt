/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.signal

import java.util.Locale
import java.util.regex.Pattern

data class ParsedSmsTraffic(
    val totalGb: Float? = null,
    val usedGb: Float? = null,
    val remainingGb: Float? = null,
    val isSuccess: Boolean = false,
    val summaryText: String = ""
)

object SmsTrafficParser {

    /**
     * 智能解析四大运营商 (移动 10086 / 联通 10010 / 电信 10000 / 广电 10099) 短信流量回执
     */
    fun parseCarrierSms(body: String, fallbackTotalQuotaGb: Float = 200f): ParsedSmsTraffic {
        if (body.isBlank()) return ParsedSmsTraffic(isSuccess = false, summaryText = "短信内容为空")

        val cleanBody = body.replace("\r", "").replace("\n", " ").trim()

        // 提取所有流量数值
        val numberPattern = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(GB|MB|KB|G|M|兆|字节)", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcher = numberPattern.matcher(cleanBody)
        
        data class TrafficMatch(val valueGb: Float, val startPos: Int, val rawStr: String)
        val matches = mutableListOf<TrafficMatch>()
        
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unitStr = matcher.group(2)?.lowercase() ?: "gb"
            val valFloat = numStr.toFloatOrNull() ?: continue
            
            // 过滤掉 "4G" 和 "5G" 作为网络制式的误判 (如果紧接着是 流量/网络/套餐 等非标点中文字符)
            val fullMatch = matcher.group(0) ?: ""
            if ((fullMatch.equals("4G", ignoreCase = true) || fullMatch.equals("5G", ignoreCase = true)) && valFloat in 4f..5f) {
                val endPos = matcher.end()
                if (endPos < cleanBody.length) {
                    val nextChar = cleanBody[endPos]
                    if (nextChar == '网' || nextChar == '络' || nextChar == '流' || nextChar == '信' || nextChar == '套' || nextChar == '手') {
                        continue // 忽略，这是 "5G流量" 等描述，不是流量数值
                    }
                }
            }

            val valInGb = when {
                unitStr.contains("m") || unitStr.contains("兆") -> valFloat / 1024f
                unitStr.contains("k") -> valFloat / (1024f * 1024f)
                else -> valFloat
            }
            matches.add(TrafficMatch(valInGb, matcher.start(), fullMatch))
        }

        var detectedTotal: Float? = null
        var detectedUsed: Float? = null
        var detectedRemaining: Float? = null

        val usedKeywords = listOf("已用", "已使用", "使用", "用去", "已消耗", "消费", "消耗")
        val remainKeywords = listOf("剩余", "余量", "还剩", "可用", "余", "尚有", "结余")
        val totalKeywords = listOf("套餐", "总量", "总额", "包含", "共包含", "共计", "总流量", "额度", "基准")

        if (matches.size == 1) {
            val m = matches[0].valueGb
            val hasUsed = usedKeywords.any { cleanBody.contains(it) }
            val hasTotal = totalKeywords.any { cleanBody.contains(it) }
            val hasRemain = remainKeywords.any { cleanBody.contains(it) }
            
            when {
                hasUsed && !hasRemain -> detectedUsed = m
                hasTotal && !hasRemain && !hasUsed -> detectedTotal = m
                else -> detectedRemaining = m // 默认猜想为剩余
            }
        } else if (matches.size >= 2) {
            // 对每个匹配项，向前寻找最近的关键字
            for (match in matches) {
                val prefix = cleanBody.substring(0, match.startPos)
                
                var bestUsedDist = Int.MAX_VALUE
                for (kw in usedKeywords) {
                    val idx = prefix.lastIndexOf(kw)
                    if (idx != -1) bestUsedDist = minOf(bestUsedDist, match.startPos - idx)
                }
                
                var bestRemainDist = Int.MAX_VALUE
                for (kw in remainKeywords) {
                    val idx = prefix.lastIndexOf(kw)
                    if (idx != -1) bestRemainDist = minOf(bestRemainDist, match.startPos - idx)
                }
                
                var bestTotalDist = Int.MAX_VALUE
                for (kw in totalKeywords) {
                    val idx = prefix.lastIndexOf(kw)
                    if (idx != -1) bestTotalDist = minOf(bestTotalDist, match.startPos - idx)
                }
                
                val minDist = minOf(bestUsedDist, bestRemainDist, bestTotalDist)
                if (minDist == Int.MAX_VALUE) continue
                
                // 距离必须在 40 个字符以内，否则认为不相关
                if (minDist > 40) continue
                
                if (minDist == bestUsedDist && detectedUsed == null) {
                    detectedUsed = match.valueGb
                } else if (minDist == bestRemainDist && detectedRemaining == null) {
                    detectedRemaining = match.valueGb
                } else if (minDist == bestTotalDist && detectedTotal == null) {
                    detectedTotal = match.valueGb
                }
            }
        }

        // 逻辑补全推导
        if (detectedTotal != null && detectedUsed != null && detectedRemaining == null) {
            detectedRemaining = (detectedTotal - detectedUsed).coerceAtLeast(0f)
        } else if (detectedTotal != null && detectedRemaining != null && detectedUsed == null) {
            detectedUsed = (detectedTotal - detectedRemaining).coerceAtLeast(0f)
        } else if (detectedUsed != null && detectedRemaining != null && detectedTotal == null) {
            detectedTotal = detectedUsed + detectedRemaining
        } else if (detectedRemaining != null && detectedTotal == null && detectedUsed == null) {
            // 只有剩余流量，使用默认/保存的套餐总量补全已用
            if (fallbackTotalQuotaGb > 0) {
                detectedTotal = fallbackTotalQuotaGb
                detectedUsed = (fallbackTotalQuotaGb - detectedRemaining).coerceAtLeast(0f)
            }
        } else if (detectedUsed != null && detectedTotal == null && detectedRemaining == null) {
            if (fallbackTotalQuotaGb > 0) {
                detectedTotal = fallbackTotalQuotaGb
                detectedRemaining = (fallbackTotalQuotaGb - detectedUsed).coerceAtLeast(0f)
            }
        }

        val success = detectedUsed != null || detectedRemaining != null || detectedTotal != null
        val summary = if (success) {
            val totalStr = String.format(Locale.US, "%.2f", detectedTotal ?: fallbackTotalQuotaGb)
            val usedStr = String.format(Locale.US, "%.2f", detectedUsed ?: 0f)
            val remStr = String.format(Locale.US, "%.2f", detectedRemaining ?: 0f)
            "自动识别成功：套餐 ${totalStr}GB | 已用 ${usedStr}GB | 剩余 ${remStr}GB"
        } else {
            "识别失败：未能判定具体使用或剩余流量，请确保短信中含有 GB/MB 单位"
        }

        return ParsedSmsTraffic(
            totalGb = detectedTotal,
            usedGb = detectedUsed,
            remainingGb = detectedRemaining,
            isSuccess = success,
            summaryText = summary
        )
    }
}
