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

public val Icons.Outlined.Lan: ImageVector
    get() {
        if (_lan != null) {
            return _lan!!
        }
        _lan =
            materialIcon(name = "Outlined.Lan") {
                materialPath {
                    moveTo(13.0f, 22.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-7.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(-5.0f)
                    verticalLineTo(9.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineTo(2.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(7.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineTo(6.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineTo(3.0f)
                    verticalLineToRelative(7.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-7.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineTo(22.0f)
                    close()
                    moveTo(10.0f, 7.0f)
                    verticalLineTo(4.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(10.0f)
                    close()
                    moveTo(9.0f, 17.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(5.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineTo(9.0f)
                    close()
                    moveTo(19.0f, 17.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineTo(19.0f)
                    close()
                }
            }
        return _lan!!
    }

private var _lan: ImageVector? = null
