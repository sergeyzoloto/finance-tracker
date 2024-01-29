import { render, waitFor, screen } from '@testing-library/react';
import { UserContextProvider, UserContext } from '../UserContext.jsx';
import React from 'react';

describe('UserContext', () => {
  it('UserContext provides values', async () => {
    const TestComponent = () => {
      const { userInfo, setUserInfo } = React.useContext(UserContext);

      React.useEffect(() => {
        setUserInfo({ name: 'test' });
      }, [setUserInfo]);

      return <div data-testid="user-info">{JSON.stringify(userInfo)}</div>;
    };

    render(
      <UserContextProvider>
        <TestComponent />
      </UserContextProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('user-info').textContent).toEqual(
        JSON.stringify({ name: 'test' }),
      );
    });
  });
});
