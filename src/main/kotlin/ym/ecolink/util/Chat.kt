package ym.ecolink.util

import org.bukkit.ChatColor

fun String.colorize(): String = ChatColor.translateAlternateColorCodes('&', this)
