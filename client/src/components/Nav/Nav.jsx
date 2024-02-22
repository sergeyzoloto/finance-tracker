import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import classNames from '../../utils/classNames';

import TEST_ID from './Nav.testid';

/* Styles */
const styles = {
  DEFAULT_BUTTON: 'block rounded-md px-3 py-2 m-1',
  CURRENT_BUTTON: 'bg-gray-700 text-white',
  INACTIVE_BUTTON:
    'text-gray-400 bg-gray-300 hover:bg-gray-800 hover:text-white',
  NAV: 'bg-gray-200 space-y-1 px-2 pb-3 pt-2',
  MENU_BUTTON: 'md:hidden rounded-md px-3 py-2',
};

/* Navigation data */
const navigation = [
  { name: 'Home', path: '/', current: true, dataTestId: TEST_ID.linkToHome },
  {
    name: 'Users',
    path: '/user/list',
    current: false,
    dataTestId: TEST_ID.linkToUsers,
  },
  {
    name: 'Create new user',
    path: '/user/create',
    current: false,
    dataTestId: TEST_ID.createUserLink,
  },
  {
    name: 'Login',
    path: '/user/login',
    current: false,
    dataTestId: TEST_ID.loginLink,
  },
];

/* Disclosure component */
const Disclosure = ({ children }) => {
  const [open, setOpen] = useState(false);

  return children({ open, setOpen });
};

/* Nav component */
const Nav = () => {
  const [links, setLinks] = useState(navigation);

  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    const updatedLinks = links.map((link) => {
      return {
        ...link,
        current: location.pathname === link.path,
      };
    });

    setLinks(updatedLinks);
  }, [location.pathname]);

  const handleClick = (link) => {
    navigate(link.path);

    const updatedLinks = links.map((item) => {
      return {
        ...item,
        current: item.dataTestId === link.dataTestId,
      };
    });

    setLinks(updatedLinks);
  };

  return (
    <nav className={styles.NAV}>
      <Disclosure>
        {({ open, setOpen }) => (
          <div onMouseLeave={(event) => setOpen(false)}>
            {/* Mobile menu button */}
            <button
              className={styles.MENU_BUTTON}
              onClick={() => setOpen(!open)}
              data-testid={TEST_ID.menuButton}
            >
              {open ? 'Close' : 'Menu'}
            </button>
            {/* Links */}
            <div
              data-testid={TEST_ID.linksContainer}
              className={`md:flex ${open ? 'block' : 'hidden'} md:block`}
            >
              {links.map((item) => (
                <button
                  key={item.name}
                  className={classNames(
                    item.current
                      ? styles.CURRENT_BUTTON
                      : styles.INACTIVE_BUTTON,
                    styles.DEFAULT_BUTTON,
                  )}
                  onClick={() => handleClick(item)}
                  data-testid={item.dataTestId}
                >
                  {item.name}
                </button>
              ))}
            </div>
          </div>
        )}
      </Disclosure>
    </nav>
  );
};

export default Nav;
