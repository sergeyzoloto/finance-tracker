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
    <div data-testid={TEST_ID.container}>
      <h1>What should the user be?</h1>
      <form onSubmit={handleSubmit}>
        <Input
          name="email"
          value={email}
          onChange={(value) => setEmail(value)}
          data-testid={TEST_ID.emailInput}
        />
        <Input
          name="password"
          value={password}
          onChange={(value) => setPassword(value)}
          data-testid={TEST_ID.nameInput}
        />
        <button type="submit" data-testid={TEST_ID.submitButton}>
          Submit
        </button>
      </form>
      {statusComponent}
    </div>
  );
};

export default CreateUser;
