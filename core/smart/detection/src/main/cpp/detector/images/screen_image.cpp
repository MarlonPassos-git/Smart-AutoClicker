/*
 * Copyright (C) 2025 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include <opencv2/imgproc/imgproc.hpp>
#include "screen_image.hpp"
#include "../../utils/correction.hpp"

using namespace smartautoclicker;


void ScreenImage::processNewData(std::unique_ptr<cv::Mat> newData, const char* metricsTag) {
    if (!newData || newData->empty() || requiresCorrection(metricsTag)) return;

    this->colorMat = std::move(*newData);
    grayValid = false;
    hsvValid = false;
    hsvCroppedPixels = 0;
}

cv::Mat ScreenImage::cropColor(const cv::Rect &roi) const {
    if (this->colorMat.empty()) return {};
    return cropMat(this->colorMat, roi);
}

cv::Mat ScreenImage::cropGray(const cv::Rect &roi) const {
    const cv::Mat& gray = getGrayMat();
    if (gray.empty()) return {};
    return cropMat(gray, roi);
}

cv::Mat ScreenImage::cropHsv(const cv::Rect &roi) const {
    if (hsvValid) return cropMat(getHsvMat(), roi);

    cv::Mat colorCrop = cropColor(roi);
    if (colorCrop.empty()) return {};

    // Small template candidates don't need a full-screen HSV conversion. If repeated crops would
    // consume one screen's worth of pixels, switch to the shared cache for the rest of this frame.
    // This bounds extra conversion work while preserving exactly the same color conversion steps.
    if (hsvCroppedPixels >= colorMat.total() - colorCrop.total()) {
        return cropMat(getHsvMat(), roi);
    }
    hsvCroppedPixels += colorCrop.total();

    cv::Mat rgbCrop;
    cv::Mat hsvCrop;
    cv::cvtColor(colorCrop, rgbCrop, cv::COLOR_RGBA2RGB);
    cv::cvtColor(rgbCrop, hsvCrop, cv::COLOR_RGB2HSV);
    return hsvCrop;
}

cv::Mat ScreenImage::cropMat(const cv::Mat& mat, const cv::Rect& roi) {
    cv::Rect imageBounds(0, 0, mat.cols, mat.rows);
    cv::Rect validRoi = roi & imageBounds;

    if (validRoi.width <= 0 || validRoi.height <= 0) return {};

    return (mat)(validRoi);
}
