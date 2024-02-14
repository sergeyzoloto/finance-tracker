import React from 'react';
import { Link } from 'react-router-dom';

import TEST_ID from './Nav.testid';

const Nav = () => {
  return (
    <ul>
      <Link to="/" data-testid={TEST_ID.linkToHome}>
        <li>Home</li>
      </Link>
      <Link to="/user/list" data-testid={TEST_ID.linkToUsers}>
        <li>Users</li>
      </Link>
      <Link to="/user/create" data-testid={TEST_ID.createUserLink}>
        <li>Create new user</li>
      </Link>
      <Link to="/user/login" data-testid={TEST_ID.loginLink}>
        <li>Login</li>
      </Link>
    </ul>
  );
};

export default Nav;
