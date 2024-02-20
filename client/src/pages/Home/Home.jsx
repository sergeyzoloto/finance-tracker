import React from 'react';

import TEST_ID from './Home.testid';

/* Styles */
const HOME_PAGE = 'p-2 my-2';

function Home() {
  return (
    <div data-testid={TEST_ID.container} className={HOME_PAGE}>
      <h1>Homepage</h1>
      <p>This is it!</p>
    </div>
  );
}

export default Home;
