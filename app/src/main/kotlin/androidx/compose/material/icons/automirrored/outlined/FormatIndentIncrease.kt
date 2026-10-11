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

public val Icons.AutoMirrored.Outlined.FormatIndentIncrease: ImageVector
    get() {
        if (_formatIndentIncrease != null) {
            return _formatIndentIncrease!!
        }
        _formatIndentIncrease =
            materialIcon(
                name = "AutoMirrored.Outlined.FormatIndentIncrease",
                autoMirror = true,
            ) {
                materialPath {
                    moveTo(3.0f, 21.0f)
                    horizontalLineToRelative(18.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(3.0f, 19.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(3.0f, 8.0f)
                    verticalLineToRelative(8.0f)
                    lineToRelative(4.0f, -4.0f)
                    lineToRelative(-4.0f, -4.0f)
                    close()
                    moveTo(11.0f, 17.0f)
                    horizontalLineToRelative(10.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(11.0f, 15.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(3.0f, 3.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(18.0f)
                    lineTo(21.0f, 3.0f)
                    lineTo(3.0f, 3.0f)
                    close()
                    moveTo(11.0f, 9.0f)
                    horizontalLineToRelative(10.0f)
                    lineTo(21.0f, 7.0f)
                    lineTo(11.0f, 7.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(11.0f, 13.0f)
                    horizontalLineToRelative(10.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(11.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    close()
                }
            }
        return _formatIndentIncrease!!
    }

private var _formatIndentIncrease: ImageVector? = null
