import React from 'react';
import TEST_ID from './DeleteUserModal.testid';

const Modal = ({
  password,
  setPassword,
  handleConfirmDelete,
  handleModalClose,
  error,
  isLoading,
  success,
}) => {
  let statusContent = null;

  if (isLoading) {
    statusContent = (
      <div data-testid={TEST_ID.loadingContainer}>loading...</div>
    );
  } else if (error != null || error != undefined) {
    statusContent = (
      <div data-testid={TEST_ID.errorContainer}>{error.toString()}</div>
    );
  } else if (success) {
    statusContent = (
      <div data-testid={TEST_ID.successContainer}>
        User deleted successfully
      </div>
    );
  }

  let input = (
    <input
      type="password"
      value={password}
      onChange={(event) => setPassword(event.target.value)}
      className="mt-2 border rounded p-2"
      placeholder="Enter password"
      data-testid={TEST_ID.passwordInput}
    />
  );

  let buttons = (
    <div className="mt-4 flex justify-end">
      <button
        onClick={handleConfirmDelete}
        data-testid={TEST_ID.confirmButton}
        className="mr-2 bg-blue-500 text-white rounded p-2"
      >
        Confirm
      </button>
      <button
        onClick={handleModalClose}
        data-testid={TEST_ID.cancelButton}
        className="bg-red-500 text-white rounded p-2"
      >
        Cancel
      </button>
    </div>
  );

  let content = (
    <div className="bg-white p-4 rounded">
      {!success && <h2>Confirm user deletion</h2>}
      {!success && input}
      {statusContent && statusContent}
      {!success && buttons}
    </div>
  );

  return (
    <div
      className="fixed inset-0 flex items-center justify-center bg-black bg-opacity-50"
      data-testid={TEST_ID.container}
    >
      {content}
    </div>
  );
};

export default Modal;
