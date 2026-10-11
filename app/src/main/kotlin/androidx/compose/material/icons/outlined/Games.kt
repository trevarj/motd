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

public val Icons.Outlined.Games: ImageVector
    get() {
        if (_games != null) {
            return _games!!
        }
        _games =
            materialIcon(name = "Outlined.Games") {
                materialPath {
                    moveTo(13.0f, 4.0f)
                    verticalLineToRelative(2.67f)
                    lineToRelative(-1.0f, 1.0f)
                    lineToRelative(-1.0f, -1.0f)
                    lineTo(11.0f, 4.0f)
                    horizontalLineToRelative(2.0f)
                    moveToRelative(7.0f, 7.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-2.67f)
                    lineToRelative(-1.0f, -1.0f)
                    lineToRelative(1.0f, -1.0f)
                    lineTo(20.0f, 11.0f)
                    moveTo(6.67f, 11.0f)
                    lineToRelative(1.0f, 1.0f)
                    lineToRelative(-1.0f, 1.0f)
                    lineTo(4.0f, 13.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.67f)
                    moveTo(12.0f, 16.33f)
                    lineToRelative(1.0f, 1.0f)
                    lineTo(13.0f, 20.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.67f)
                    lineToRelative(1.0f, -1.0f)
                    moveTo(15.0f, 2.0f)
                    lineTo(9.0f, 2.0f)
                    verticalLineToRelative(5.5f)
                    lineToRelative(3.0f, 3.0f)
                    lineToRelative(3.0f, -3.0f)
                    lineTo(15.0f, 2.0f)
                    close()
                    moveTo(22.0f, 9.0f)
                    horizontalLineToRelative(-5.5f)
                    lineToRelative(-3.0f, 3.0f)
                    lineToRelative(3.0f, 3.0f)
                    lineTo(22.0f, 15.0f)
                    lineTo(22.0f, 9.0f)
                    close()
                    moveTo(7.5f, 9.0f)
                    lineTo(2.0f, 9.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(5.5f)
                    lineToRelative(3.0f, -3.0f)
                    lineToRelative(-3.0f, -3.0f)
                    close()
                    moveTo(12.0f, 13.5f)
                    lineToRelative(-3.0f, 3.0f)
                    lineTo(9.0f, 22.0f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(-5.5f)
                    lineToRelative(-3.0f, -3.0f)
                    close()
                }
            }
        return _games!!
    }

private var _games: ImageVector? = null
