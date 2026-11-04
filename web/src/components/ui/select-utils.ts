import * as React from 'react';

export interface OptionItem {
  value: string;
  label: string;
}

export function nodeText(node: React.ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(nodeText).join('');
  if (React.isValidElement(node)) {
    return nodeText((node.props as { children?: React.ReactNode }).children);
  }
  return '';
}

export function collectOptions(children: React.ReactNode): OptionItem[] {
  const items: OptionItem[] = [];
  React.Children.forEach(children, (child) => {
    if (!React.isValidElement(child) || child.type !== 'option') return;
    const props = child.props as { value?: string | number | readonly string[]; children?: React.ReactNode };
    const label = nodeText(props.children).trim();
    items.push({ value: String(props.value ?? label), label });
  });
  return items;
}

export interface PopupPositionParams {
  triggerRect: { top: number; bottom: number; left: number; right: number; width: number; height: number };
  popupRect: { width: number; height: number };
  viewportWidth: number;
  viewportHeight: number;
  padding?: number;
}

export interface PopupPositionResult {
  top: number;
  left: number;
  minWidth: number;
  maxHeight: number;
  openAbove: boolean;
}

export function calculatePopupPosition({
  triggerRect,
  popupRect,
  viewportWidth,
  viewportHeight,
  padding = 8,
}: PopupPositionParams): PopupPositionResult {
  const minWidth = Math.max(triggerRect.width, 140);
  const popupWidth = Math.max(popupRect.width || minWidth, minWidth);
  const popupHeight = popupRect.height || 160;

  // Horizontal clamping: ensure popup stays within [padding, viewportWidth - padding]
  const maxLeft = Math.max(padding, viewportWidth - popupWidth - padding);
  const left = Math.max(padding, Math.min(triggerRect.left, maxLeft));

  // Vertical position: calculate space above and below
  const spaceBelow = viewportHeight - triggerRect.bottom - padding;
  const spaceAbove = triggerRect.top - padding;

  const openAbove = spaceBelow < 150 && spaceAbove > spaceBelow;

  let top: number;
  let maxHeight: number;

  if (openAbove) {
    top = Math.max(padding, triggerRect.top - popupHeight - 6);
    maxHeight = Math.max(100, Math.min(260, spaceAbove - 6));
  } else {
    top = triggerRect.bottom + 6;
    maxHeight = Math.max(100, Math.min(260, spaceBelow - 6));
  }

  return {
    top,
    left,
    minWidth,
    maxHeight,
    openAbove,
  };
}
