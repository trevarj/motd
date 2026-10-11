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

public val Icons.Outlined.Bedtime: ImageVector
    get() {
        if (_bedtime != null) {
            return _bedtime!!
        }
        _bedtime =
            materialIcon(name = "Outlined.Bedtime") {
                materialPath {
                    moveTo(9.27f, 4.49f)
                    curveToRelative(-1.63f, 7.54f, 3.75f, 12.41f, 7.66f, 13.8f)
                    curveTo(15.54f, 19.38f, 13.81f, 20.0f, 12.0f, 20.0f)
                    curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                    curveTo(4.0f, 8.55f, 6.2f, 5.6f, 9.27f, 4.49f)
                    moveTo(11.99f, 2.01f)
                    curveTo(6.4f, 2.01f, 2.0f, 6.54f, 2.0f, 12.0f)
                    curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
                    curveToRelative(3.71f, 0.0f, 6.93f, -2.02f, 8.66f, -5.02f)
                    curveToRelative(-7.51f, -0.25f, -12.09f, -8.43f, -8.32f, -14.97f)
                    curveTo(12.22f, 2.01f, 12.11f, 2.01f, 11.99f, 2.01f)
                    lineTo(11.99f, 2.01f)
                    close()
                }
            }
        return _bedtime!!
    }

private var _bedtime: ImageVector? = null
