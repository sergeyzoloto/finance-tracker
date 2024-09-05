import type { Meta, StoryObj } from "@storybook/react";
import { Button } from "@/components/ui/button";

const meta: Meta<typeof Button> = {
  title: "Components/Button",
  component: Button,
  argTypes: {
    onClick: { action: "clicked" },
    variant: {
      control: { type: "select" },
      options: [
        "default",
        "destructive",
        "outline",
        "secondary",
        "ghost",
        "link",
      ],
    },
    size: {
      control: { type: "select" },
      options: ["default", "sm", "lg", "icon"],
    },
  },
};

export default meta;
type Story = StoryObj<typeof Button>;

export const Default: Story = {
  args: {
    variant: "default",
    size: "default",
    label: "Default Button",
  },
};

export const Small: Story = {
  args: {
    variant: "default",
    size: "sm",
    label: "Small Button",
  },
};

export const Large: Story = {
  args: {
    variant: "default",
    size: "lg",
    label: "Large Button",
  },
};

export const Icon: Story = {
  args: {
    variant: "default",
    size: "icon",
    label: "Icon Button",
  },
};

export const Ghost: Story = {
  args: {
    variant: "ghost",
    size: "default",
    label: "Ghost Button",
  },
};

export const Link: Story = {
  args: {
    variant: "link",
    size: "default",
    label: "Link Button",
  },
};
