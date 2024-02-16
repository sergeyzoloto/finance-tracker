import React from 'react';
import {
  render,
  waitFor,
  screen,
  fireEvent,
  waitForElementToBeRemoved,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import UserList from '../UserList.jsx';
import TEST_ID_USER_LIST from '../UserList.testid.js';
import TEST_ID_DELETE_USER_MODAL from '../components/DeleteUserModal/DeleteUserModal.testid.js';

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
    // Mock our fetch with users
    const testEmail1 = 'john@doe.com';
    const testEmail2 = 'johny@be.good';

    const users = [
      { _id: 'u---1', password: 'qwerty123456', email: testEmail1 },
      { _id: 'u---2', password: '123456qwerty', email: testEmail2 },
    ];

    fetch.mockResponseOnce(getUsersSuccessMock(users));

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
    // Mock our fetch with users
    const users = [
      { _id: 'u---1', password: 'qwerty123456', email: 'john@doe.com' },
      { _id: 'u---2', password: '123456qwerty', email: 'johny@be.good' },
    ];

    fetch.mockResponseOnce(getUsersSuccessMock(users));

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

    // Check that the delete button is rendered for each user
    users.forEach((user) => {
      expect(
        screen.getByTestId(`${TEST_ID_USER_LIST.deleteUserButton}-${user._id}`),
      ).toBeInTheDocument();
    });
  });

  it('Opens the delete modal when the delete button is clicked', async () => {
    // Mock our fetch with users
    const testEmail1 = 'john@doe.com';
    const testEmail2 = 'johny@be.good';

    const users = [
      { _id: 'u---1', password: 'qwerty123456', email: testEmail1 },
      { _id: 'u---2', password: '123456qwerty', email: testEmail2 },
    ];

    fetch.mockResponseOnce(getUsersSuccessMock(users));

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

    // Click the delete button
    fireEvent.click(
      screen.getByTestId(
        `${TEST_ID_USER_LIST.deleteUserButton}-${users[0]._id}`,
      ),
    );

    // Check that the delete modal is opened
    waitFor(() =>
      expect(
        screen.getByTestId(TEST_ID_DELETE_USER_MODAL.container),
      ).toBeInTheDocument(),
    );
  });

  it('Closes the delete modal when the cancel button is clicked', async () => {
    // Mock our fetch with users
    const testEmail1 = 'john@doe.com';
    const testEmail2 = 'johny@be.good';

    const users = [
      { _id: 'u---1', password: 'qwerty123456', email: testEmail1 },
      { _id: 'u---2', password: '123456qwerty', email: testEmail2 },
    ];

    fetch.mockResponseOnce(getUsersSuccessMock(users));

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

    let deleteButton = screen.getByTestId(
      `${TEST_ID_USER_LIST.deleteUserButton}-${users[0]._id}`,
    );

    // Click the delete button
    fireEvent.click(deleteButton);

    // Check that the delete modal is opened
    await waitFor(() =>
      expect(
        screen.getByTestId(TEST_ID_DELETE_USER_MODAL.container),
      ).toBeInTheDocument(),
    );

    let cancelButton = screen.getByTestId(
      TEST_ID_DELETE_USER_MODAL.cancelButton,
    );

    // Check that the delete modal is opened
    await waitFor(() => expect(cancelButton).toBeInTheDocument());

    // Click the cancel button
    fireEvent.click(screen.getByTestId(TEST_ID_DELETE_USER_MODAL.cancelButton));

    // Check that the delete modal is closed
    waitFor(() =>
      expect(
        screen.getByTestId(TEST_ID_DELETE_USER_MODAL.container),
      ).not.toBeInTheDocument(),
    );
  });
});
