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

public val Icons.Filled.FormatItalic: ImageVector
    get() {
        if (_formatItalic != null) {
            return _formatItalic!!
        }
        _formatItalic =
            materialIcon(name = "Filled.FormatItalic") {
                materialPath {
                    moveTo(10.0f, 4.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(2.21f)
                    lineToRelative(-3.42f, 8.0f)
                    horizontalLineTo(6.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineToRelative(-2.21f)
                    lineToRelative(3.42f, -8.0f)
                    horizontalLineTo(18.0f)
                    verticalLineTo(4.0f)
                    close()
                }
            }
        return _formatItalic!!
    }

private var _formatItalic: ImageVector? = null
