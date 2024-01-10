import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';
import './index.css';
import AppWrapper from './AppWrapper.jsx';

ReactDOM.createRoot(document.getElementById('root')).render(
  <AppWrapper>
    <App />
  </AppWrapper>,
);
