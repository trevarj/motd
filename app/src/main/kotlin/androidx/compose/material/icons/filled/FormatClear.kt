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

public val Icons.Filled.FormatClear: ImageVector
    get() {
        if (_formatClear != null) {
            return _formatClear!!
        }
        _formatClear =
            materialIcon(name = "Filled.FormatClear") {
                materialPath {
                    moveTo(3.27f, 5.0f)
                    lineTo(2.0f, 6.27f)
                    lineToRelative(6.97f, 6.97f)
                    lineTo(6.5f, 19.0f)
                    horizontalLineToRelative(3.0f)
                    lineToRelative(1.57f, -3.66f)
                    lineTo(16.73f, 21.0f)
                    lineTo(18.0f, 19.73f)
                    lineTo(3.55f, 5.27f)
                    lineTo(3.27f, 5.0f)
                    close()
                    moveTo(6.0f, 5.0f)
                    verticalLineToRelative(0.18f)
                    lineTo(8.82f, 8.0f)
                    horizontalLineToRelative(2.4f)
                    lineToRelative(-0.72f, 1.68f)
                    lineToRelative(2.1f, 2.1f)
                    lineTo(14.21f, 8.0f)
                    horizontalLineTo(20.0f)
                    verticalLineTo(5.0f)
                    horizontalLineTo(6.0f)
                    close()
                }
            }
        return _formatClear!!
    }

private var _formatClear: ImageVector? = null
