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

public val Icons.Outlined.School: ImageVector
    get() {
        if (_school != null) {
            return _school!!
        }
        _school =
            materialIcon(name = "Outlined.School") {
                materialPath {
                    moveTo(12.0f, 3.0f)
                    lineTo(1.0f, 9.0f)
                    lineToRelative(4.0f, 2.18f)
                    verticalLineToRelative(6.0f)
                    lineTo(12.0f, 21.0f)
                    lineToRelative(7.0f, -3.82f)
                    verticalLineToRelative(-6.0f)
                    lineToRelative(2.0f, -1.09f)
                    lineTo(21.0f, 17.0f)
                    horizontalLineToRelative(2.0f)
                    lineTo(23.0f, 9.0f)
                    lineTo(12.0f, 3.0f)
                    close()
                    moveTo(18.82f, 9.0f)
                    lineTo(12.0f, 12.72f)
                    lineTo(5.18f, 9.0f)
                    lineTo(12.0f, 5.28f)
                    lineTo(18.82f, 9.0f)
                    close()
                    moveTo(17.0f, 15.99f)
                    lineToRelative(-5.0f, 2.73f)
                    lineToRelative(-5.0f, -2.73f)
                    verticalLineToRelative(-3.72f)
                    lineTo(12.0f, 15.0f)
                    lineToRelative(5.0f, -2.73f)
                    verticalLineToRelative(3.72f)
                    close()
                }
            }
        return _school!!
    }

private var _school: ImageVector? = null
