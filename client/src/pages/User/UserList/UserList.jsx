import React, { useEffect, useState } from 'react';

import useFetch from '../../../hooks/useFetch';
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
  const { isLoading, error, performFetch, cancelFetch } = useFetch(
    '/user',
    (response) => {
      setUsers(response.result);
    },
  );

  useEffect(() => {
    performFetch();

    return cancelFetch;
  }, []);

  const handleDelete = (userId) => {
    console.log(`Implement delete logic here: DELETE ${userId}`);
  };

  let content = null;

  if (isLoading) {
    content = <div data-testid={TEST_ID.loadingContainer}>loading...</div>;
  } else if (error != null) {
    content = (
      <div data-testid={TEST_ID.errorContainer}>{error.toString()}</div>
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
      </>
    );
  }

  return <div data-testid={TEST_ID.container}>{content}</div>;
};

export default UserList;
