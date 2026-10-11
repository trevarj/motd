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

public val Icons.Filled.FormatColorText: ImageVector
    get() {
        if (_formatColorText != null) {
            return _formatColorText!!
        }
        _formatColorText =
            materialIcon(name = "Filled.FormatColorText") {
                materialPath {
                    moveTo(2.0f, 20.0f)
                    horizontalLineToRelative(20.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineTo(2.0f)
                    verticalLineTo(20.0f)
                    close()
                    moveTo(5.49f, 17.0f)
                    horizontalLineToRelative(2.42f)
                    lineToRelative(1.27f, -3.58f)
                    horizontalLineToRelative(5.65f)
                    lineTo(16.09f, 17.0f)
                    horizontalLineToRelative(2.42f)
                    lineTo(13.25f, 3.0f)
                    horizontalLineToRelative(-2.5f)
                    lineTo(5.49f, 17.0f)
                    close()
                    moveTo(9.91f, 11.39f)
                    lineToRelative(2.03f, -5.79f)
                    horizontalLineToRelative(0.12f)
                    lineToRelative(2.03f, 5.79f)
                    horizontalLineTo(9.91f)
                    close()
                }
            }
        return _formatColorText!!
    }

private var _formatColorText: ImageVector? = null
