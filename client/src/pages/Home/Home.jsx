import React from 'react';

import TEST_ID from './Home.testid';

function Home() {
  return (
    <div data-testid={TEST_ID.container}>
      <h1>Homepage</h1>
      <p>This is it!</p>
    </div>
  );
}

export default Home;
