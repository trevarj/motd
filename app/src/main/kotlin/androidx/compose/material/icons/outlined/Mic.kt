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

public val Icons.Outlined.Mic: ImageVector
    get() {
        if (_mic != null) {
            return _mic!!
        }
        _mic =
            materialIcon(name = "Outlined.Mic") {
                materialPath {
                    moveTo(12.0f, 14.0f)
                    curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
                    verticalLineTo(5.0f)
                    curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                    reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                    verticalLineToRelative(6.0f)
                    curveTo(9.0f, 12.66f, 10.34f, 14.0f, 12.0f, 14.0f)
                    close()
                }
                materialPath {
                    moveTo(17.0f, 11.0f)
                    curveToRelative(0.0f, 2.76f, -2.24f, 5.0f, -5.0f, 5.0f)
                    reflectiveCurveToRelative(-5.0f, -2.24f, -5.0f, -5.0f)
                    horizontalLineTo(5.0f)
                    curveToRelative(0.0f, 3.53f, 2.61f, 6.43f, 6.0f, 6.92f)
                    verticalLineTo(21.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(-3.08f)
                    curveToRelative(3.39f, -0.49f, 6.0f, -3.39f, 6.0f, -6.92f)
                    horizontalLineTo(17.0f)
                    close()
                }
            }
        return _mic!!
    }

private var _mic: ImageVector? = null
