/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.MarkChatUnread: ImageVector
    get() {
        if (_markChatUnread != null) {
            return _markChatUnread!!
        }
        _markChatUnread =
            materialIcon(name = "Outlined.MarkChatUnread") {
                materialPath {
                    moveTo(22.0f, 6.98f)
                    verticalLineTo(16.0f)
                    curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                    horizontalLineTo(6.0f)
                    lineToRelative(-4.0f, 4.0f)
                    verticalLineTo(4.0f)
                    curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                    horizontalLineToRelative(10.1f)
                    curveTo(14.04f, 2.32f, 14.0f, 2.66f, 14.0f, 3.0f)
                    reflectiveCurveToRelative(0.04f, 0.68f, 0.1f, 1.0f)
                    horizontalLineTo(4.0f)
                    verticalLineToRelative(12.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineTo(7.9f)
                    curveTo(20.74f, 7.75f, 21.42f, 7.42f, 22.0f, 6.98f)
                    close()
                    moveTo(16.0f, 3.0f)
                    curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                    reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                    reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
                    reflectiveCurveTo(16.0f, 1.34f, 16.0f, 3.0f)
                    close()
                }
            }
        return _markChatUnread!!
    }

private var _markChatUnread: ImageVector? = null
