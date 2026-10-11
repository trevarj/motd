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

public val Icons.Outlined.SentimentSatisfiedAlt: ImageVector
    get() {
        if (_sentimentSatisfiedAlt != null) {
            return _sentimentSatisfiedAlt!!
        }
        _sentimentSatisfiedAlt =
            materialIcon(name = "Outlined.SentimentSatisfiedAlt") {
                materialPath {
                    moveTo(15.5f, 9.5f)
                    moveToRelative(-1.5f, 0.0f)
                    arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                    arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
                }
                materialPath {
                    moveTo(8.5f, 9.5f)
                    moveToRelative(-1.5f, 0.0f)
                    arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                    arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
                }
                materialPath {
                    moveTo(12.0f, 16.0f)
                    curveToRelative(-1.48f, 0.0f, -2.75f, -0.81f, -3.45f, -2.0f)
                    lineTo(6.88f, 14.0f)
                    curveToRelative(0.8f, 2.05f, 2.79f, 3.5f, 5.12f, 3.5f)
                    reflectiveCurveToRelative(4.32f, -1.45f, 5.12f, -3.5f)
                    horizontalLineToRelative(-1.67f)
                    curveToRelative(-0.69f, 1.19f, -1.97f, 2.0f, -3.45f, 2.0f)
                    close()
                    moveTo(11.99f, 2.0f)
                    curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                    reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                    curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                    reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                    close()
                    moveTo(12.0f, 20.0f)
                    curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                    reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                    reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                    reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                    close()
                }
            }
        return _sentimentSatisfiedAlt!!
    }

private var _sentimentSatisfiedAlt: ImageVector? = null
