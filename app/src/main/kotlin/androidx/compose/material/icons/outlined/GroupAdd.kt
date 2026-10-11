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

public val Icons.Outlined.GroupAdd: ImageVector
    get() {
        if (_groupAdd != null) {
            return _groupAdd!!
        }
        _groupAdd =
            materialIcon(name = "Outlined.GroupAdd") {
                materialPath {
                    moveTo(22.0f, 9.0f)
                    lineToRelative(0.0f, -2.0f)
                    lineToRelative(-2.0f, 0.0f)
                    lineToRelative(0.0f, 2.0f)
                    lineToRelative(-2.0f, 0.0f)
                    lineToRelative(0.0f, 2.0f)
                    lineToRelative(2.0f, 0.0f)
                    lineToRelative(0.0f, 2.0f)
                    lineToRelative(2.0f, 0.0f)
                    lineToRelative(0.0f, -2.0f)
                    lineToRelative(2.0f, 0.0f)
                    lineToRelative(0.0f, -2.0f)
                    close()
                }
                materialPath {
                    moveTo(8.0f, 12.0f)
                    curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                    reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                    reflectiveCurveTo(4.0f, 5.79f, 4.0f, 8.0f)
                    reflectiveCurveTo(5.79f, 12.0f, 8.0f, 12.0f)
                    close()
                    moveTo(8.0f, 6.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    reflectiveCurveTo(6.0f, 9.1f, 6.0f, 8.0f)
                    reflectiveCurveTo(6.9f, 6.0f, 8.0f, 6.0f)
                    close()
                }
                materialPath {
                    moveTo(8.0f, 13.0f)
                    curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(-3.0f)
                    curveTo(16.0f, 14.34f, 10.67f, 13.0f, 8.0f, 13.0f)
                    close()
                    moveTo(14.0f, 18.0f)
                    horizontalLineTo(2.0f)
                    verticalLineToRelative(-0.99f)
                    curveTo(2.2f, 16.29f, 5.3f, 15.0f, 8.0f, 15.0f)
                    reflectiveCurveToRelative(5.8f, 1.29f, 6.0f, 2.0f)
                    verticalLineTo(18.0f)
                    close()
                }
                materialPath {
                    moveTo(12.51f, 4.05f)
                    curveTo(13.43f, 5.11f, 14.0f, 6.49f, 14.0f, 8.0f)
                    reflectiveCurveToRelative(-0.57f, 2.89f, -1.49f, 3.95f)
                    curveTo(14.47f, 11.7f, 16.0f, 10.04f, 16.0f, 8.0f)
                    reflectiveCurveTo(14.47f, 4.3f, 12.51f, 4.05f)
                    close()
                }
                materialPath {
                    moveTo(16.53f, 13.83f)
                    curveTo(17.42f, 14.66f, 18.0f, 15.7f, 18.0f, 17.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(-3.0f)
                    curveTo(20.0f, 15.55f, 18.41f, 14.49f, 16.53f, 13.83f)
                    close()
                }
            }
        return _groupAdd!!
    }

private var _groupAdd: ImageVector? = null
