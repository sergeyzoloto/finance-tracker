import type { Meta, StoryObj } from "@storybook/react";
import WelcomePage from "../pages/WelcomePage/WelcomePage";

const meta: Meta<typeof WelcomePage> = {
  title: "Pages/WelcomePage",
  component: WelcomePage,
};

export default meta;
type Story = StoryObj<typeof WelcomePage>;

export const Default: Story = {};
