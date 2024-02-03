import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import App from '../App';
import TEST_ID_HOME from '../pages/Home/Home.testid';

test('renders a react page', () => {
  render(
    <MemoryRouter initialEntries={['/']}>
      <App />
    </MemoryRouter>,
  );
  const homeElement = screen.getByTestId(TEST_ID_HOME.container);
  expect(homeElement).toBeInTheDocument();
});
