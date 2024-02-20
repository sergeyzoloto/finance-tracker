import React, { useEffect, useState } from 'react';

import useFetch from '../../../hooks/useFetch';
import DeleteUserModal from './components/DeleteUserModal/DeleteUserModal';
import TEST_ID from './UserList.testid';
import classNames from '../../../utils/classNames';

/* Styles */
const styles = {
  CONTAINER: 'py-4',
  DELETE_BUTTON_ANIMATION: 'hover:bg-white  transition-all duration-300',
  DELETE_BUTTON_FORM: 'visible rounded-full font-bold size-8',
  USER_LIST: 'max-w-screen-sm mx-auto py-2',
  USER_LIST_ITEM_FORM: 'group flex justify-between items-center m-px py-2 px-4',
  USER_LIST_ITEM_ANIMATION: 'hover:bg-gray-100 transition-all duration-300',
};

const DeleteButton = ({ onDelete, userId }) => (
  <button
    className={classNames(
      styles.DELETE_BUTTON_ANIMATION,
      styles.DELETE_BUTTON_FORM,
    )}
    onClick={onDelete}
    data-testid={`${TEST_ID.deleteUserButton}-${userId}`}
  >
    <svg
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1"
        d="M6 18L18 6M6 6l12 12"
      />
    </svg>
  </button>
);

const UserList = () => {
  const [users, setUsers] = useState(null);
  const [showModal, setShowModal] = useState(false);
  const [successfullyDeleted, setSuccessfullyDeleted] = useState(false);
  const [password, setPassword] = useState('');
  const [deleteUserId, setDeleteUserId] = useState(null);

  const {
    isLoading: listIsLoading,
    error: listError,
    performFetch: listPerformFetch,
    cancelFetch: listCancelFetch,
  } = useFetch('/user', (response) => {
    setUsers(response.result);
  });

  const {
    isLoading: deleteIsLoading,
    error: deleteError,
    performFetch: deletionPerformFetch,
    cancelFetch: cancelDeleteFetch,
  } = useFetch('/user/delete', (response) => {
    if (response.success) {
      setSuccessfullyDeleted(true);
      setTimeout(() => {
        handleModalClose();
        listPerformFetch();
      }, 4000);
    }
  });

  const handleModalClose = () => {
    setShowModal(false);
    setPassword('');
    setSuccessfullyDeleted(false);
    setDeleteUserId(null);
  };

  const handleConfirmDelete = () => {
    deletionPerformFetch({
      method: 'DELETE',
      body: JSON.stringify({ user: { id: deleteUserId, password } }),
      credentials: 'include', // save cookies inside react app
    });

    return cancelDeleteFetch;
  };

  useEffect(() => {
    listPerformFetch();

    return listCancelFetch;
  }, []);

  const handleDelete = (userId) => {
    setDeleteUserId(userId);
    setShowModal(true);
  };

  let content = null;

  if (listIsLoading) {
    content = <div data-testid={TEST_ID.loadingContainer}>loading...</div>;
  } else if (listError != null) {
    content = (
      <div data-testid={TEST_ID.errorContainer}>{listError.toString()}</div>
    );
  } else {
    content = (
      <>
        <h1>These are the users</h1>
        <ul
          data-testid={TEST_ID.userList}
          data-loaded={users != null}
          className={styles.USER_LIST}
        >
          {users &&
            users.map((user) => {
              return (
                <li
                  key={user._id}
                  data-elementid={user._id}
                  className={classNames(
                    styles.USER_LIST_ITEM_ANIMATION,
                    styles.USER_LIST_ITEM_FORM,
                  )}
                >
                  {user.email}
                  <DeleteButton
                    onDelete={() => handleDelete(user._id)}
                    userId={user._id}
                  />
                </li>
              );
            })}
        </ul>
        {showModal && (
          <DeleteUserModal
            password={password}
            setPassword={setPassword}
            handleConfirmDelete={handleConfirmDelete}
            handleModalClose={handleModalClose}
            isLoading={deleteIsLoading}
            error={deleteError}
            success={successfullyDeleted}
          />
        )}
      </>
    );
  }

  return (
    <div data-testid={TEST_ID.container} className={styles.CONTAINER}>
      {content}
    </div>
  );
};

export default UserList;
