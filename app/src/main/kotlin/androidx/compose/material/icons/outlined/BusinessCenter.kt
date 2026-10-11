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

public val Icons.Outlined.BusinessCenter: ImageVector
    get() {
        if (_businessCenter != null) {
            return _businessCenter!!
        }
        _businessCenter =
            materialIcon(name = "Outlined.BusinessCenter") {
                materialPath {
                    moveTo(20.0f, 7.0f)
                    horizontalLineToRelative(-4.0f)
                    lineTo(16.0f, 5.0f)
                    lineToRelative(-2.0f, -2.0f)
                    horizontalLineToRelative(-4.0f)
                    lineTo(8.0f, 5.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(4.0f, 7.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(5.0f)
                    curveToRelative(0.0f, 0.75f, 0.4f, 1.38f, 1.0f, 1.73f)
                    lineTo(3.0f, 19.0f)
                    curveToRelative(0.0f, 1.11f, 0.89f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(14.0f)
                    curveToRelative(1.11f, 0.0f, 2.0f, -0.89f, 2.0f, -2.0f)
                    verticalLineToRelative(-3.28f)
                    curveToRelative(0.59f, -0.35f, 1.0f, -0.99f, 1.0f, -1.72f)
                    lineTo(22.0f, 9.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(10.0f, 5.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-4.0f)
                    lineTo(10.0f, 5.0f)
                    close()
                    moveTo(4.0f, 9.0f)
                    horizontalLineToRelative(16.0f)
                    verticalLineToRelative(5.0f)
                    horizontalLineToRelative(-5.0f)
                    verticalLineToRelative(-3.0f)
                    lineTo(9.0f, 11.0f)
                    verticalLineToRelative(3.0f)
                    lineTo(4.0f, 14.0f)
                    lineTo(4.0f, 9.0f)
                    close()
                    moveTo(13.0f, 15.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(19.0f, 19.0f)
                    lineTo(5.0f, 19.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(1.0f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(-1.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(3.0f)
                    close()
                }
            }
        return _businessCenter!!
    }

private var _businessCenter: ImageVector? = null
