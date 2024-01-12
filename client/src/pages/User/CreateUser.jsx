import React, { useEffect, useState } from 'react';

import Input from '../../components/Input';
import useFetch from '../../hooks/useFetch';
import TEST_ID from './CreateUser.testid';

const CreateUser = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const onSuccess = () => {
    setPassword('');
    setEmail('');
  };
  const { isLoading, error, performFetch, cancelFetch } = useFetch(
    '/user/create',
    onSuccess,
  );

  useEffect(() => {
    return cancelFetch;
  }, []);

  const handleSubmit = (e) => {
    e.preventDefault();
    performFetch({
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ user: { email, password } }),
    });
  };

  let statusComponent = null;
  if (error != null) {
    statusComponent = (
      <div data-testid={TEST_ID.errorContainer}>
        Error while trying to create user: {error.toString()}
      </div>
    );
  } else if (isLoading) {
    statusComponent = (
      <div data-testid={TEST_ID.loadingContainer}>Creating user....</div>
    );
  }

  return (
    <div
      data-testid={TEST_ID.container}
      className="flex flex-col w-64 relative my-0 mx-auto gap-2 min-w-fit p-2"
    >
      <h1>What should the user be?</h1>
      <form
        onSubmit={handleSubmit}
        className="flex flex-col w-64 relative my-0 mx-auto gap-2 min-w-fit p-2 box-border"
      >
        <Input
          name="email"
          placeholder="email"
          value={email}
          onChange={(value) => setEmail(value)}
          data-testid={TEST_ID.emailInput}
          className="flexbox border-2 border-solid block w-full p-2 bg-white border-neutral-100 hover:border-neutral-200 text-center rounded max-w-sm"
        />
        <Input
          name="password"
          placeholder="password"
          value={password}
          onChange={(value) => setPassword(value)}
          data-testid={TEST_ID.passwordInput}
          className="flexbox border-2 border-solid block w-full p-2 bg-white border-neutral-100 hover:border-neutral-200 text-center rounded max-w-sm"
        />
        <button
          type="submit"
          data-testid={TEST_ID.submitButton}
          className="w-full block bg-slate-400 border-none text-white rounded py-2"
        >
          Submit
        </button>
      </form>
      {statusComponent}
    </div>
  );
};

export default CreateUser;
