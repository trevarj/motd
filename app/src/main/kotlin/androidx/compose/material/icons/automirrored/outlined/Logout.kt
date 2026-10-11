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

package androidx.compose.material.icons.automirrored.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Outlined.Logout: ImageVector
    get() {
        if (_logout != null) {
            return _logout!!
        }
        _logout =
            materialIcon(name = "AutoMirrored.Outlined.Logout", autoMirror = true) {
                materialPath {
                    moveTo(17.0f, 8.0f)
                    lineToRelative(-1.41f, 1.41f)
                    lineTo(17.17f, 11.0f)
                    horizontalLineTo(9.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(8.17f)
                    lineToRelative(-1.58f, 1.58f)
                    lineTo(17.0f, 16.0f)
                    lineToRelative(4.0f, -4.0f)
                    lineTo(17.0f, 8.0f)
                    close()
                    moveTo(5.0f, 5.0f)
                    horizontalLineToRelative(7.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(5.0f)
                    curveTo(3.9f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
                    verticalLineToRelative(14.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(7.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(5.0f)
                    verticalLineTo(5.0f)
                    close()
                }
            }
        return _logout!!
    }

private var _logout: ImageVector? = null
