/*
 * Ani
 * Copyright (C) 2022-2024 Him188
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.him188.ani.datasources.api.topic

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Episode(
    val raw: String,
) : Comparable<Episode> {
    override fun compareTo(other: Episode): Int {
        return this.raw.compareTo(other.raw) // TODO: 2022/8/4 check episode compare
    }

    override fun toString(): String = raw
}

@Serializable
data class Alliance(
    val id: String,
    val name: String,
)