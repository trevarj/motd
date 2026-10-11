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

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.FlashOff: ImageVector
    get() {
        if (_flashOff != null) {
            return _flashOff!!
        }
        _flashOff =
            materialIcon(name = "Filled.FlashOff") {
                materialPath {
                    moveTo(3.27f, 3.0f)
                    lineTo(2.0f, 4.27f)
                    lineToRelative(5.0f, 5.0f)
                    verticalLineTo(13.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(9.0f)
                    lineToRelative(3.58f, -6.14f)
                    lineTo(17.73f, 20.0f)
                    lineTo(19.0f, 18.73f)
                    lineTo(3.27f, 3.0f)
                    close()
                    moveTo(17.0f, 10.0f)
                    horizontalLineToRelative(-4.0f)
                    lineToRelative(4.0f, -8.0f)
                    horizontalLineTo(7.0f)
                    verticalLineToRelative(2.18f)
                    lineToRelative(8.46f, 8.46f)
                    lineTo(17.0f, 10.0f)
                    close()
                }
            }
        return _flashOff!!
    }

private var _flashOff: ImageVector? = null
