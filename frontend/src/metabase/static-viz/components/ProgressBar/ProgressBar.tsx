import React from "react";
import { scaleLinear } from "@visx/scale";
import { Group } from "@visx/group";
import { ClipPath } from "@visx/clip-path";
import { formatNumber } from "../../lib/numbers";
import { Text } from "../Text";
import { Pointer } from "./Pointer";
import { CheckMarkIcon } from "./CheckMarkIcon";
import {
  createPalette,
  getBarText,
  getColors,
  calculatePointerLabelShift,
} from "./utils";
import { ProgressBarData } from "./types";

const layout = {
  width: 440,
  height: 110,
  margin: {
    top: 40,
    right: 40,
    left: 40,
  },
  barHeight: 40,
  borderRadius: 5,
  labelsMargin: 16,
  iconSize: 20,
  pointer: {
    width: 20,
    height: 10,
  },
  fontSize: 13,
};

interface ProgressBarProps {
  data: ProgressBarData;
  settings: {
    color: string;
    format: any;
  };
}

const ProgressBar = ({
  data,
  settings: { color, format },
}: ProgressBarProps) => {
  const palette = createPalette(color);
  const colors = getColors(data, palette);
  const barWidth = layout.width - layout.margin.left - layout.margin.right;

  const xMin = layout.margin.left;
  const xMax = xMin + barWidth;

  const labelsY = layout.margin.top + layout.barHeight + layout.labelsMargin;

  const xScale = scaleLinear({
    domain: [0, Math.max(data.goal, data.value)],
    range: [0, barWidth],
  });

  const currentX = xScale(Math.min(data.goal, data.value));

  const pointerY = layout.margin.top - layout.pointer.height * 1.5;
  const pointerX = xMin + Math.max(xScale(data.value), 0);

  const barText = getBarText(data);

  const valueText = formatNumber(data.value, format);

  const valueTextShift = calculatePointerLabelShift(
    valueText,
    pointerX,
    xMin,
    xMax,
    layout.pointer.width,
    layout.fontSize,
  );

  return (
    <svg width={layout.width} height={layout.height}>
      <ClipPath id="rounded-bar">
        <rect
          width={barWidth}
          height={layout.barHeight}
          rx={layout.borderRadius}
        />
      </ClipPath>
      <Group clipPath={`url(#rounded-bar)`} top={layout.margin.top} left={xMin}>
        <rect
          width={barWidth}
          height={layout.barHeight}
          fill={colors.backgroundBar}
        />
        <rect
          width={currentX}
          height={layout.barHeight}
          fill={colors.foregroundBar}
        />
        {barText && (
          <>
            <CheckMarkIcon
              size={layout.iconSize}
              color="white"
              x={10}
              y={(layout.barHeight - layout.iconSize) / 2}
            />
            <Text
              fontSize={layout.fontSize}
              textAnchor="start"
              color="white"
              x={layout.iconSize + 16}
              y={layout.barHeight / 2}
              dominantBaseline="central"
              fill="white"
            >
              {barText}
            </Text>
          </>
        )}
      </Group>
      <Group left={pointerX} top={pointerY}>
        <Text
          fontSize={layout.fontSize}
          textAnchor={"middle"}
          dy="-0.4em"
          dx={valueTextShift}
        >
          {valueText}
        </Text>
        <Pointer
          width={layout.pointer.width}
          height={layout.pointer.height}
          fill={colors.pointer}
        />
      </Group>
      <Group top={labelsY}>
        <Text
          fontSize={layout.fontSize}
          textAnchor="start"
          alignmentBaseline="baseline"
          x={layout.margin.left}
        >
          {formatNumber(0, format)}
        </Text>
        <Text fontSize={layout.fontSize} textAnchor="end" x={xMax}>
          {`Goal ${formatNumber(data.goal, format)}`}
        </Text>
      </Group>
    </svg>
  );
};

export default ProgressBar;
