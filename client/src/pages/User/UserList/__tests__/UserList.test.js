import React from 'react';
import {
  render,
  waitFor,
  screen,
  waitForElementToBeRemoved,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import UserList from '../UserList.jsx';
import TEST_ID_USER_LIST from '../UserList.testid.js';
import {
  getUsersSuccessMock,
  getUsersFailedMock,
} from '../../../../__testUtils__/fetchUserMocks';
import { asSlowResponse } from '../../../../__testUtils__/fetchMocks';

beforeEach(() => {
  fetch.resetMocks();
});

describe('UserList', () => {
  it('Renders without a problem', async () => {
    // Mock our fetch
    fetch.mockResponseOnce(getUsersSuccessMock());

    render(
      <MemoryRouter>
        <UserList />
      </MemoryRouter>,
    );

    // Wait until data is loaded
    await waitFor(() =>
      expect(screen.getByTestId(TEST_ID_USER_LIST.userList)).toHaveAttribute(
        'data-loaded',
        'true',
      ),
    );

    // Check that the page has rendered
    expect(screen.getByTestId(TEST_ID_USER_LIST.container)).toBeInTheDocument();
  });

  it('Renders the users given by the backend', async () => {
    const testPassword1 = 'qwerty123456';
    const testEmail1 = 'john@doe.com';
    const testPassword2 = '123456qwerty';
    const testEmail2 = 'johny@be.good';

    // Mock our fetch with a user
    fetch.mockResponseOnce(
      getUsersSuccessMock([
        { _id: 'u---1', password: testPassword1, email: testEmail1 },
        { _id: 'u---2', password: testPassword2, email: testEmail2 },
      ]),
    );
    render(
      <MemoryRouter>
        <UserList />
      </MemoryRouter>,
    );

    // Wait until data is loaded
    await waitFor(() =>
      expect(screen.getByTestId(TEST_ID_USER_LIST.userList)).toHaveAttribute(
        'data-loaded',
        'true',
      ),
    );

    // Check the information is on the page.
    // We only check that the name is somewhere on the page, so {exact: false}
    expect(screen.getByText(testEmail1, { exact: false })).toBeInTheDocument();
    expect(screen.getByText(testEmail2, { exact: false })).toBeInTheDocument();
  });

  it('Shows loading when the data is still loading', async () => {
    // Mock our fetch with a slow response
    fetch.mockResponseOnce(asSlowResponse(getUsersSuccessMock()));

    render(
      <MemoryRouter>
        <UserList />
      </MemoryRouter>,
    );

    expect(
      screen.getByTestId(TEST_ID_USER_LIST.loadingContainer),
    ).toBeInTheDocument();

    // Loading div should be removed after the load is complete
    await waitForElementToBeRemoved(() =>
      screen.getByTestId(TEST_ID_USER_LIST.loadingContainer),
    );
  });

  it('Shows an error if the server responds with an error', async () => {
    // Mock our fetch with a failed response
    fetch.mockResponseOnce(getUsersFailedMock());

    render(
      <MemoryRouter>
        <UserList />
      </MemoryRouter>,
    );

    expect(
      screen.queryByTestId(TEST_ID_USER_LIST.errorContainer),
    ).not.toBeInTheDocument();

    // Wait to see that the error container is being shown
    await waitFor(() =>
      expect(
        screen.getByTestId(TEST_ID_USER_LIST.errorContainer),
      ).toBeInTheDocument(),
    );
  });

  it('Delete button rendered and handles click', async () => {
    const userId1 = 'u---1';
    const testPassword1 = 'qwerty123456';
    const testEmail1 = 'john@doe.com';
    const userId2 = 'u---2';
    const testPassword2 = '123456qwerty';
    const testEmail2 = 'johny@be.good';

    // Mock our fetch with a user
    fetch.mockResponseOnce(
      getUsersSuccessMock([
        { _id: userId1, password: testPassword1, email: testEmail1 },
        { _id: userId2, password: testPassword2, email: testEmail2 },
      ]),
    );
    render(
      <MemoryRouter>
        <UserList />
      </MemoryRouter>,
    );

    // Wait until data is loaded
    await waitFor(() =>
      expect(screen.getByTestId(TEST_ID_USER_LIST.userList)).toHaveAttribute(
        'data-loaded',
        'true',
      ),
    );

    // Check the delete button are on the page
    expect(
      screen.getByTestId(`${TEST_ID_USER_LIST.deleteUserButton}-${userId1}`),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId(`${TEST_ID_USER_LIST.deleteUserButton}-${userId2}`),
    ).toBeInTheDocument();
  });
});
