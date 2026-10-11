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

public val Icons.Outlined.Spellcheck: ImageVector
    get() {
        if (_spellcheck != null) {
            return _spellcheck!!
        }
        _spellcheck =
            materialIcon(name = "Outlined.Spellcheck") {
                materialPath {
                    moveTo(12.45f, 16.0f)
                    horizontalLineToRelative(2.09f)
                    lineTo(9.43f, 3.0f)
                    lineTo(7.57f, 3.0f)
                    lineTo(2.46f, 16.0f)
                    horizontalLineToRelative(2.09f)
                    lineToRelative(1.12f, -3.0f)
                    horizontalLineToRelative(5.64f)
                    lineToRelative(1.14f, 3.0f)
                    close()
                    moveTo(6.43f, 11.0f)
                    lineTo(8.5f, 5.48f)
                    lineTo(10.57f, 11.0f)
                    lineTo(6.43f, 11.0f)
                    close()
                    moveTo(21.59f, 11.59f)
                    lineToRelative(-8.09f, 8.09f)
                    lineTo(9.83f, 16.0f)
                    lineToRelative(-1.41f, 1.41f)
                    lineToRelative(5.09f, 5.09f)
                    lineTo(23.0f, 13.0f)
                    lineToRelative(-1.41f, -1.41f)
                    close()
                }
            }
        return _spellcheck!!
    }

private var _spellcheck: ImageVector? = null
