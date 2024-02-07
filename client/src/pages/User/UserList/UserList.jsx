import React, { useEffect, useState } from 'react';

import useFetch from '../../../hooks/useFetch';
import Modal from '../../../components/Modal/Modal';
import TEST_ID from './UserList.testid';

const DeleteButton = ({ onDelete, userId }) => (
  <button
    className="visible hover:bg-white font-bold transition-all duration-200 rounded-full size-10 flex justify-center items-center"
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
    isLoading: deletionIsLoading,
    error: deletionError,
    performFetch: deletionPerformFetch,
    cancelFetch: deletionCancelFetch,
  } = useFetch('/user', (response) => {
    if (response.success) {
      setTimeout(() => {
        handleModalClose();
      }, 4000);
    }
  });

  const handleModalClose = () => {
    setShowModal(false);
    setPassword('');
    setDeleteUserId(null);
  };

  const handleConfirmDelete = () => {
    deletionPerformFetch({
      method: 'DELETE',
      body: JSON.stringify({ user: { userId: deleteUserId, password } }),
    });
  };

  useEffect(() => {
    listPerformFetch();

    return listCancelFetch;
  }, []);

  const handleDelete = (userId) => {
    console.log(`Implement delete logic here: DELETE ${userId}`);
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
          className="max-w-screen-sm mx-auto"
        >
          {users &&
            users.map((user) => {
              return (
                <li
                  key={user._id}
                  data-elementid={user._id}
                  className="group flex justify-between items-center m-px p-2 transition-all duration-200 hover:bg-gray-100"
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
          <Modal
            password={password}
            setPassword={setPassword}
            handleConfirmDelete={handleConfirmDelete}
            handleModalClose={handleModalClose}
          />
        )}
      </>
    );
  }

  return <div data-testid={TEST_ID.container}>{content}</div>;
};

export default UserList;
