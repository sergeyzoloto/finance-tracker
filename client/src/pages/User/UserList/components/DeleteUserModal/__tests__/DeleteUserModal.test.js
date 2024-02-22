// FILEPATH: /home/serge/Projects/finance-tracker/client/src/components/Modal/Modal.test.js

import React from 'react';
import { render, fireEvent } from '@testing-library/react';
import DeleteUserModal from '../DeleteUserModal';
import TEST_ID from '../DeleteUserModal.testid';

describe('Modal', () => {
  const mockPassword = 'password123';
  const mockSetPassword = jest.fn();
  const mockHandleConfirmDelete = jest.fn();
  const mockHandleModalClose = jest.fn();
  const mockError = null;
  const mockIsLoading = false;
  const mockSuccess = false;

  it('renders with the correct props', () => {
    const { getByTestId } = render(
      <DeleteUserModal
        password={mockPassword}
        setPassword={mockSetPassword}
        handleConfirmDelete={mockHandleConfirmDelete}
        handleModalClose={mockHandleModalClose}
        error={mockError}
        isLoading={mockIsLoading}
        success={mockSuccess}
      />,
    );

    // Check that the password input field receives the correct props
    const passwordInput = getByTestId(TEST_ID.passwordInput);
    expect(passwordInput.value).toBe(mockPassword);
    expect(passwordInput.placeholder).toBe('Enter password');

    // Check that the confirm button receives the correct props
    const confirmButton = getByTestId(TEST_ID.confirmButton);
    expect(confirmButton.textContent).toBe('Confirm');
    fireEvent.click(confirmButton);
    expect(mockHandleConfirmDelete).toHaveBeenCalled();

    // Check that the cancel button receives the correct props
    const cancelButton = getByTestId(TEST_ID.cancelButton);
    expect(cancelButton.textContent).toBe('Cancel');
    fireEvent.click(cancelButton);
    expect(mockHandleModalClose).toHaveBeenCalled();
  });

  it('calls setPassword when the input value changes', () => {
    const setPassword = jest.fn();
    const { getByPlaceholderText } = render(
      <DeleteUserModal
        password="test password"
        setPassword={setPassword}
        handleConfirmDelete={() => {}}
        handleModalClose={() => {}}
      />,
    );

    fireEvent.change(getByPlaceholderText('Enter password'), {
      target: { value: 'new password' },
    });

    expect(setPassword).toHaveBeenCalledWith('new password');
  });

  it('renders loading status when isLoading is true', () => {
    const { getByTestId } = render(
      <DeleteUserModal
        password={mockPassword}
        setPassword={mockSetPassword}
        handleConfirmDelete={mockHandleConfirmDelete}
        handleModalClose={mockHandleModalClose}
        error={mockError}
        isLoading={true}
        success={mockSuccess}
      />,
    );
    const loadingElement = getByTestId(TEST_ID.loadingContainer);
    expect(loadingElement).toBeInTheDocument();
    expect(loadingElement.textContent).toBe('loading...');
  });

  it('renders error status when error is not null or undefined', () => {
    const mockError = 'An error occurred';
    const { getByTestId } = render(
      <DeleteUserModal
        password={mockPassword}
        setPassword={mockSetPassword}
        handleConfirmDelete={mockHandleConfirmDelete}
        handleModalClose={mockHandleModalClose}
        error={mockError}
        isLoading={mockIsLoading}
        success={mockSuccess}
      />,
    );
    const errorElement = getByTestId(TEST_ID.errorContainer);
    expect(errorElement).toBeInTheDocument();
    expect(errorElement.textContent).toBe(mockError);
  });

  test('renders success status when success is true', () => {
    const { getByTestId } = render(
      <DeleteUserModal
        password={mockPassword}
        setPassword={mockSetPassword}
        handleConfirmDelete={mockHandleConfirmDelete}
        handleModalClose={mockHandleModalClose}
        error={mockError}
        isLoading={mockIsLoading}
        success={true}
      />,
    );
    const successElement = getByTestId(TEST_ID.successContainer);
    expect(successElement).toBeInTheDocument();
    expect(successElement.textContent).toBe('User deleted successfully');
  });
});
