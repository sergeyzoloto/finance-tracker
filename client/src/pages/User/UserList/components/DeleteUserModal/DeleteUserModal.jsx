import React from 'react';
import TEST_ID from './DeleteUserModal.testid';

/* Styles */
const styles = {
  CONTAINER:
    'fixed inset-0 flex items-center justify-center bg-black bg-opacity-50',
  MODAL: 'bg-white p-4 rounded',
  PASSWORD_INPUT: 'mt-2 border rounded p-2',
  BUTTONS_CONTAINER: 'mt-4 flex justify-end',
  CONFIRM_BUTTON: 'mr-2 bg-gray-600 text-white rounded p-2',
  CANCEL_BUTTON: 'bg-red-500 text-white rounded p-2',
};

const DeleteUserModal = ({
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
      className={styles.PASSWORD_INPUT}
      placeholder="Enter password"
      data-testid={TEST_ID.passwordInput}
    />
  );

  let buttons = (
    <div className={styles.BUTTONS_CONTAINER}>
      <button
        onClick={handleConfirmDelete}
        data-testid={TEST_ID.confirmButton}
        className={styles.CONFIRM_BUTTON}
      >
        Confirm
      </button>
      <button
        onClick={handleModalClose}
        data-testid={TEST_ID.cancelButton}
        className={styles.CANCEL_BUTTON}
      >
        Cancel
      </button>
    </div>
  );

  let content = (
    <div className={styles.MODAL}>
      {!success && <h2>Confirm user deletion</h2>}
      {!success && input}
      {statusContent && statusContent}
      {!success && buttons}
    </div>
  );

  return (
    <div className={styles.CONTAINER} data-testid={TEST_ID.container}>
      {content}
    </div>
  );
};

export default DeleteUserModal;
