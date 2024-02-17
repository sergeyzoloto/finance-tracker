import React, { useState } from 'react';
import { Link } from 'react-router-dom';

import TEST_ID from './Nav.testid';

// Disclosure component
const Disclosure = ({ children }) => {
  const [open, setOpen] = useState(false);

  return children({ open, setOpen });
};

const Nav = () => {
  return (
    <nav className="bg-gray-200">
      <Disclosure>
        {({ open, setOpen }) => (
          <div onMouseLeave={(e) => setOpen(false)}>
            <button
              className="md:hidden"
              onClick={() => setOpen(!open)}
              data-testid={TEST_ID.menuButton}
            >
              {open ? 'Close' : 'Menu'}
            </button>
            <div className={`md:flex ${open ? 'block' : 'hidden'} md:block`}>
              <ul className="md:flex">
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
            </div>
          </div>
        )}
      </Disclosure>
    </nav>
  );
};

export default Nav;
