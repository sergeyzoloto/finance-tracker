import React from 'react';
import { render, fireEvent, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

/**
 * We use the App component to test here as it will do the routing for us.
 * This allows our test to be more user centric!
 */
import App from '../../App';
import TEST_ID_HOME from '../../pages/Home/Home.testid';
import TEST_ID_USER_LIST from '../../pages/User/UserList/UserList.testid.js';
import TEST_ID_CREATE_USER from '../../pages/User/CreateUser/CreateUser.testid.js';
import TEST_ID_LOGIN_PAGE from '../../pages/User/LoginPage/LoginPage.testid.js';
import TEST_ID_NAV from '../Nav.testid';
import { getUsersSuccessMock } from '../../__testUtils__/fetchUserMocks';

beforeEach(() => {
  fetch.resetMocks();
});

describe('Navigation', () => {
  it('Clicking on the Home link should go to Home page ', async () => {
    fetch.mockResponseOnce(getUsersSuccessMock());

    render(
      <MemoryRouter history={history} initialEntries={['/user']}>
        <App />
      </MemoryRouter>,
    );

    expect(
      screen.queryByTestId(TEST_ID_HOME.container),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId(TEST_ID_NAV.linkToHome));

    await waitFor(() =>
      expect(screen.getByTestId(TEST_ID_HOME.container)).toBeInTheDocument(),
    );
  });

  it('Clicking on the User link should go to User List page ', async () => {
    fetch.mockResponseOnce(getUsersSuccessMock());

    render(
      <MemoryRouter history={history} initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );

    expect(
      screen.queryByTestId(TEST_ID_USER_LIST.container),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId(TEST_ID_NAV.linkToUsers));

    await waitFor(() =>
      expect(
        screen.getByTestId(TEST_ID_USER_LIST.container),
      ).toBeInTheDocument(),
    );

    // Wait until data is loaded
    await waitFor(() =>
      expect(screen.getByTestId(TEST_ID_USER_LIST.userList)).toHaveAttribute(
        'data-loaded',
        'true',
      ),
    );
  });

  it('Clicking on the Create User link should go to Create User page', async () => {
    fetch.mockResponseOnce(getUsersSuccessMock());

    render(
      <MemoryRouter history={history} initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );

    expect(screen.getByTestId(TEST_ID_NAV.createUserLink)).toBeInTheDocument();

    fireEvent.click(screen.getByTestId(TEST_ID_NAV.createUserLink));

    await waitFor(() =>
      expect(
        screen.getByTestId(TEST_ID_CREATE_USER.container),
      ).toBeInTheDocument(),
    );
  });

  it('Clicking on the Login link should go to Login page', async () => {
    fetch.mockResponseOnce(getUsersSuccessMock());

    render(
      <MemoryRouter history={history} initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );

    expect(screen.getByTestId(TEST_ID_NAV.loginLink)).toBeInTheDocument();

    fireEvent.click(screen.getByTestId(TEST_ID_NAV.loginLink));

    await waitFor(() =>
      expect(
        screen.getByTestId(TEST_ID_LOGIN_PAGE.container),
      ).toBeInTheDocument(),
    );
  });
});
