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

public val Icons.Outlined.Style: ImageVector
    get() {
        if (_style != null) {
            return _style!!
        }
        _style =
            materialIcon(name = "Outlined.Style") {
                materialPath {
                    moveTo(2.53f, 19.65f)
                    lineToRelative(1.34f, 0.56f)
                    verticalLineToRelative(-9.03f)
                    lineToRelative(-2.43f, 5.86f)
                    curveToRelative(-0.41f, 1.02f, 0.08f, 2.19f, 1.09f, 2.61f)
                    close()
                    moveTo(22.03f, 15.95f)
                    lineTo(17.07f, 3.98f)
                    curveToRelative(-0.31f, -0.75f, -1.04f, -1.21f, -1.81f, -1.23f)
                    curveToRelative(-0.26f, 0.0f, -0.53f, 0.04f, -0.79f, 0.15f)
                    lineTo(7.1f, 5.95f)
                    curveToRelative(-0.75f, 0.31f, -1.21f, 1.03f, -1.23f, 1.8f)
                    curveToRelative(-0.01f, 0.27f, 0.04f, 0.54f, 0.15f, 0.8f)
                    lineToRelative(4.96f, 11.97f)
                    curveToRelative(0.31f, 0.76f, 1.05f, 1.22f, 1.83f, 1.23f)
                    curveToRelative(0.26f, 0.0f, 0.52f, -0.05f, 0.77f, -0.15f)
                    lineToRelative(7.36f, -3.05f)
                    curveToRelative(1.02f, -0.42f, 1.51f, -1.59f, 1.09f, -2.6f)
                    close()
                    moveTo(12.83f, 19.75f)
                    lineTo(7.87f, 7.79f)
                    lineToRelative(7.35f, -3.04f)
                    horizontalLineToRelative(0.01f)
                    lineToRelative(4.95f, 11.95f)
                    lineToRelative(-7.35f, 3.05f)
                    close()
                }
                materialPath {
                    moveTo(11.0f, 9.0f)
                    moveToRelative(-1.0f, 0.0f)
                    arcToRelative(1.0f, 1.0f, 0.0f, true, true, 2.0f, 0.0f)
                    arcToRelative(1.0f, 1.0f, 0.0f, true, true, -2.0f, 0.0f)
                }
                materialPath {
                    moveTo(5.88f, 19.75f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(1.45f)
                    lineToRelative(-3.45f, -8.34f)
                    verticalLineToRelative(6.34f)
                    close()
                }
            }
        return _style!!
    }

private var _style: ImageVector? = null
